package mesosphere.marathon
package integration.facades

import com.typesafe.scalalogging.StrictLogging
import java.io.File
import java.util.Date

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import de.heikoseeberger.akkasse.EventStreamUnmarshalling
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import mesosphere.marathon.core.event.{ EventSubscribers, Subscribe, Unsubscribe }
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.integration.setup.{ RestResult, SprayHttpResponse }
import mesosphere.marathon.raml.{ App, AppUpdate, GroupInfo, GroupUpdate, Pod, PodConversion, PodInstanceStatus, PodStatus, Raml }
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.util.Retry
import org.slf4j.LoggerFactory
import play.api.libs.functional.syntax._
import play.api.libs.json.JsArray
import spray.client.pipelining._
import spray.http._
import spray.httpx.PlayJsonSupport
import akka.http.scaladsl.{ Http => AkkaHttp }
import akka.http.scaladsl.client.RequestBuilding.{ Get => AkkaGet }
import akka.http.scaladsl.model.{ MediaType => AkkaMediaType }
import akka.http.scaladsl.model.headers.{ Accept => AkkaAccept }
import akka.http.scaladsl.unmarshalling.{ Unmarshal => AkkaUnmarshal }
import de.heikoseeberger.akkasse.ServerSentEvent

import scala.collection.immutable.Seq
import scala.concurrent.Await.result
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * GET /apps will deliver something like Apps instead of List[App]
  * Needed for dumb jackson.
  */
case class ITAppDefinition(app: App)
case class ITListAppsResult(apps: Seq[App])
case class ITAppVersions(versions: Seq[Timestamp])
case class ITListTasks(tasks: Seq[ITEnrichedTask])
case class ITDeploymentPlan(version: String, deploymentId: String)
case class ITHealthCheckResult(taskId: String, firstSuccess: Date, lastSuccess: Date, lastFailure: Date, consecutiveFailures: Int, alive: Boolean)
case class ITDeploymentResult(version: Timestamp, deploymentId: String)
case class ITEnrichedTask(
    appId: String,
    id: String,
    host: String,
    ports: Option[Seq[Int]],
    startedAt: Option[Date],
    stagedAt: Option[Date],
    state: String,
    version: Option[String]) {

  def launched: Boolean = startedAt.nonEmpty
  def suspended: Boolean = startedAt.isEmpty
}
case class ITLeaderResult(leader: String) {
  val port: Integer = leader.split(":")(1).toInt
}

case class ITListDeployments(deployments: Seq[ITDeployment])

case class ITQueueDelay(timeLeftSeconds: Int, overdue: Boolean)
case class ITQueueItem(app: App, count: Int, delay: ITQueueDelay)
case class ITLaunchQueue(queue: List[ITQueueItem])

case class ITDeployment(id: String, affectedApps: Seq[String], affectedPods: Seq[String])

sealed trait ITSSEEvent
/** Used to signal that the SSE stream is connected */
case object ITConnected extends ITSSEEvent

/** models each SSE published event */
case class ITEvent(eventType: String, info: Map[String, Any]) extends ITSSEEvent

/**
  * The MarathonFacade offers the REST API of a remote marathon instance
  * with all local domain objects.
  *
  * @param url the url of the remote marathon instance
  */
class MarathonFacade(
  val url: String, baseGroup: PathId, waitTime: Duration = 30.seconds)(
  implicit
  val system: ActorSystem, mat: Materializer)
    extends PlayJsonSupport
    with PodConversion with StrictLogging {
  implicit val scheduler = system.scheduler
  import SprayHttpResponse._
  import mesosphere.marathon.core.async.ExecutionContexts.global

  require(baseGroup.absolute)

  import mesosphere.marathon.api.v2.json.Formats._
  import mesosphere.marathon.integration.setup.V2TestFormats._
  import play.api.libs.json._

  implicit lazy val itAppDefinitionFormat = Json.format[ITAppDefinition]
  implicit lazy val appUpdateMarshaller = playJsonMarshaller[AppUpdate]
  implicit lazy val itListAppsResultFormat = Json.format[ITListAppsResult]
  implicit lazy val itAppVersionsFormat = Json.format[ITAppVersions]
  implicit lazy val itListTasksFormat = Json.format[ITListTasks]
  implicit lazy val itDeploymentPlanFormat = Json.format[ITDeploymentPlan]
  implicit lazy val itHealthCheckResultFormat = Json.format[ITHealthCheckResult]
  implicit lazy val itDeploymentResultFormat = Json.format[ITDeploymentResult]
  implicit lazy val itLeaderResultFormat = Json.format[ITLeaderResult]
  implicit lazy val itDeploymentFormat = Json.format[ITDeployment]
  implicit lazy val itListDeploymentsFormat = Json.format[ITListDeployments]
  implicit lazy val itQueueDelayFormat = Json.format[ITQueueDelay]
  implicit lazy val itQueueItemFormat = Json.format[ITQueueItem]
  implicit lazy val itLaunchQueueFormat = Json.format[ITLaunchQueue]

  implicit lazy val itEnrichedTaskFormat: Format[ITEnrichedTask] = (
    (__ \ "appId").format[String] ~
    (__ \ "id").format[String] ~
    (__ \ "host").format[String] ~
    (__ \ "ports").formatNullable[Seq[Int]] ~
    (__ \ "startedAt").formatNullable[Date] ~
    (__ \ "stagedAt").formatNullable[Date] ~
    (__ \ "state").format[String] ~
    (__ \ "version").formatNullable[String]
  )(ITEnrichedTask(_, _, _, _, _, _, _, _), unlift(ITEnrichedTask.unapply))

  def isInBaseGroup(pathId: PathId): Boolean = {
    pathId.path.startsWith(baseGroup.path)
  }

  def requireInBaseGroup(pathId: PathId): Unit = {
    require(isInBaseGroup(pathId), s"pathId $pathId must be in baseGroup ($baseGroup)")
  }

  def marathonSendReceive: SendReceive = {
    addHeader("Accept", "*/*") ~> sendReceive
  }

  // we don't want to lose any events and the default maxEventSize is too small (8K)
  object EventUnmarshalling extends EventStreamUnmarshalling {
    override protected def maxEventSize: Int = Int.MaxValue
    override protected def maxLineSize: Int = Int.MaxValue
  }

  /**
    * Connects to the Marathon SSE endpoint. Future completes when the http connection is established. Events are
    * streamed via the materializable-once Source.
    */
  def events(): Future[Source[ITEvent, NotUsed]] = {

    import EventUnmarshalling.fromEventStream
    val mapper = new ObjectMapper() with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)

    AkkaHttp().singleRequest(AkkaGet(s"$url/v2/events").withHeaders(AkkaAccept(AkkaMediaType.text("event-stream"))))
      .flatMap { response =>
        AkkaUnmarshal(response).to[Source[ServerSentEvent, NotUsed]]
      }
      .map { stream =>
        stream
          .map { event =>
            event.data.map { d =>
              val json = mapper.readValue[Map[String, Any]](d) // linter:ignore
              ITEvent(event.`type`.getOrElse("unknown"), json)
            }
          }
          .collect { case Some(event) => event }
      }
  }

  //app resource ----------------------------------------------

  def listAppsInBaseGroup: RestResult[List[App]] = {
    val pipeline = marathonSendReceive ~> read[ITListAppsResult]
    val res = result(pipeline(Get(s"$url/v2/apps")), waitTime)
    res.map(_.apps.filterAs(app => isInBaseGroup(PathId(app.id)))(collection.breakOut))
  }

  def app(id: PathId): RestResult[ITAppDefinition] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[ITAppDefinition]
    val getUrl: String = s"$url/v2/apps$id"
    LoggerFactory.getLogger(getClass).info(s"get url = $getUrl")
    result(pipeline(Get(getUrl)), waitTime)
  }

  def createAppV2(app: App): RestResult[App] = {
    requireInBaseGroup(PathId(app.id))
    val pipeline = marathonSendReceive ~> read[App]
    result(pipeline(Post(s"$url/v2/apps", app)), waitTime)
  }

  def deleteApp(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/apps$id?force=$force")), waitTime)
  }

  def updateApp(id: PathId, app: AppUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[ITDeploymentResult]
    val putUrl: String = s"$url/v2/apps$id?force=$force"
    LoggerFactory.getLogger(getClass).info(s"put url = $putUrl")

    result(pipeline(Put(putUrl, app)), waitTime)
  }

  def patchApp(id: PathId, app: AppUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[ITDeploymentResult]
    val putUrl: String = s"$url/v2/apps$id?force=$force"
    LoggerFactory.getLogger(getClass).info(s"put url = $putUrl")

    result(pipeline(Patch(putUrl, app)), waitTime)
  }

  def restartApp(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[ITDeploymentResult]
    result(pipeline(Post(s"$url/v2/apps$id/restart?force=$force")), waitTime)
  }

  def listAppVersions(id: PathId): RestResult[ITAppVersions] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[ITAppVersions]
    result(pipeline(Get(s"$url/v2/apps$id/versions")), waitTime)
  }

  def appVersion(id: PathId, version: Timestamp): RestResult[App] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[App]
    result(pipeline(Get(s"$url/v2/apps$id/versions/$version")), waitTime)
  }

  //pod resource ---------------------------------------------

  def listPodsInBaseGroup: RestResult[Seq[PodDefinition]] = {
    val pipeline = marathonSendReceive ~> read[Seq[Pod]]
    val res = result(pipeline(Get(s"$url/v2/pods")), waitTime)
    res.map(_.map(Raml.fromRaml(_))).map(_.filter(pod => isInBaseGroup(pod.id)))
  }

  def pod(id: PathId): RestResult[PodDefinition] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[Pod]
    val res = result(pipeline(Get(s"$url/v2/pods$id")), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def createPodV2(pod: PodDefinition): RestResult[PodDefinition] = {
    requireInBaseGroup(pod.id)
    val pipeline = marathonSendReceive ~> read[Pod]
    val res = result(pipeline(Post(s"$url/v2/pods", Raml.toRaml(pod))), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def deletePod(id: PathId, force: Boolean = false): RestResult[HttpResponse] = {
    requireInBaseGroup(id)
    val res = result(marathonSendReceive(Delete(s"$url/v2/pods$id?force=$force")), waitTime)
    RestResult.apply(res)
  }

  def updatePod(id: PathId, pod: PodDefinition, force: Boolean = false): RestResult[PodDefinition] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[Pod]
    val res = result(pipeline(Put(s"$url/v2/pods$id?force=$force", pod)), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def status(podId: PathId): RestResult[PodStatus] = {
    requireInBaseGroup(podId)
    val pipeline = marathonSendReceive ~> read[PodStatus]
    result(pipeline(Get(s"$url/v2/pods$podId::status")), waitTime)
  }

  def listPodVersions(podId: PathId): RestResult[Seq[Timestamp]] = {
    requireInBaseGroup(podId)
    val pipeline = marathonSendReceive ~> read[Seq[Timestamp]]
    result(pipeline(Get(s"$url/v2/pods$podId::versions")), waitTime)
  }

  def podVersion(podId: PathId, version: Timestamp): RestResult[PodDefinition] = {
    requireInBaseGroup(podId)
    val pipeline = marathonSendReceive ~> read[Pod]
    val res = result(pipeline(Get(s"$url/v2/pods$podId::versions/$version")), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def deleteAllInstances(podId: PathId): RestResult[List[PodInstanceStatus]] = {
    requireInBaseGroup(podId)
    val pipeline = marathonSendReceive ~> read[List[PodInstanceStatus]]
    result(pipeline(Delete(s"$url/v2/pods$podId::instances")), waitTime)
  }

  def deleteInstance(podId: PathId, instance: String): RestResult[PodInstanceStatus] = {
    requireInBaseGroup(podId)
    val pipeline = marathonSendReceive ~> read[PodInstanceStatus]
    result(pipeline(Delete(s"$url/v2/pods$podId::instances/$instance")), waitTime)
  }

  //apps tasks resource --------------------------------------

  private val log = LoggerFactory.getLogger(getClass)

  def tasks(appId: PathId): RestResult[List[ITEnrichedTask]] = {
    requireInBaseGroup(appId)
    val pipeline = marathonSendReceive ~> read[ITListTasks]
    val res = result(pipeline(Get(s"$url/v2/apps$appId/tasks")), waitTime)
    res.map(_.tasks.toList)
  }

  def killAllTasks(appId: PathId, scale: Boolean = false): RestResult[ITListTasks] = {
    requireInBaseGroup(appId)
    val pipeline = marathonSendReceive ~> read[ITListTasks]
    result(pipeline(Delete(s"$url/v2/apps$appId/tasks?scale=$scale")), waitTime)
  }

  def killAllTasksAndScale(appId: PathId): RestResult[ITDeploymentPlan] = {
    requireInBaseGroup(appId)
    val pipeline = marathonSendReceive ~> read[ITDeploymentPlan]
    result(pipeline(Delete(s"$url/v2/apps$appId/tasks?scale=true")), waitTime)
  }

  def killTask(appId: PathId, taskId: String, scale: Boolean = false): RestResult[HttpResponse] = {
    requireInBaseGroup(appId)
    val pipeline = marathonSendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/apps$appId/tasks/$taskId?scale=$scale")), waitTime)
  }

  //group resource -------------------------------------------

  def listGroupsInBaseGroup: RestResult[Set[GroupInfo]] = {
    import PathId._
    val pipeline = marathonSendReceive ~> read[GroupInfo]
    val root = result(pipeline(Get(s"$url/v2/groups")), waitTime)
    root.map(_.groups.filter(group => isInBaseGroup(group.id.toPath)))
  }

  def listGroupVersions(id: PathId): RestResult[List[String]] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[List[String]]
    result(pipeline(Get(s"$url/v2/groups$id/versions")), waitTime)
  }

  def group(id: PathId): RestResult[GroupInfo] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[GroupInfo]
    result(pipeline(Get(s"$url/v2/groups$id")), waitTime)
  }

  def createGroup(group: GroupUpdate): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(group.id.map(PathId(_)).getOrElse(throw new IllegalArgumentException("missing group.id")))
    val pipeline = marathonSendReceive ~> read[ITDeploymentResult]
    result(pipeline(Post(s"$url/v2/groups", group)), waitTime)
  }

  def deleteGroup(id: PathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/groups$id?force=$force")), waitTime)
  }

  def deleteRoot(force: Boolean): RestResult[ITDeploymentResult] = {
    val pipeline = marathonSendReceive ~> read[ITDeploymentResult]
    result(pipeline(Delete(s"$url/v2/groups?force=$force")), waitTime)
  }

  def updateGroup(id: PathId, group: GroupUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val pipeline = marathonSendReceive ~> read[ITDeploymentResult]
    result(pipeline(Put(s"$url/v2/groups$id?force=$force", group)), waitTime)
  }

  def rollbackGroup(groupId: PathId, version: Timestamp, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(groupId)
    updateGroup(groupId, GroupUpdate(None, version = Some(version.toOffsetDateTime)), force)
  }

  //deployment resource ------

  def listDeploymentsForBaseGroup(): RestResult[List[ITDeployment]] = {
    val pipeline = marathonSendReceive ~> read[List[ITDeployment]]
    result(pipeline(Get(s"$url/v2/deployments")), waitTime).map { deployments =>
      deployments.filter { deployment =>
        deployment.affectedApps.map(PathId(_)).exists(id => isInBaseGroup(id)) ||
          deployment.affectedPods.map(PathId(_)).exists(id => isInBaseGroup(id))
      }
    }
  }

  def deleteDeployment(id: String, force: Boolean = false): RestResult[HttpResponse] = {
    val pipeline = marathonSendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/deployments/$id?force=$force")), waitTime)
  }

  //event resource ---------------------------------------------

  def listSubscribers: RestResult[EventSubscribers] = {
    val pipeline = marathonSendReceive ~> read[EventSubscribers]
    result(pipeline(Get(s"$url/v2/eventSubscriptions")), waitTime)
  }

  def subscribe(callbackUrl: String): RestResult[Subscribe] = {
    val pipeline = marathonSendReceive ~> read[Subscribe]
    result(pipeline(Post(s"$url/v2/eventSubscriptions?callbackUrl=$callbackUrl")), waitTime)
  }

  def unsubscribe(callbackUrl: String): RestResult[Unsubscribe] = {
    val pipeline = marathonSendReceive ~> read[Unsubscribe]
    result(pipeline(Delete(s"$url/v2/eventSubscriptions?callbackUrl=$callbackUrl")), waitTime)
  }

  //metrics ---------------------------------------------

  def metrics(): RestResult[HttpResponse] = {
    val pipeline = marathonSendReceive ~> responseResult
    result(pipeline(Get(s"$url/metrics")), waitTime)
  }

  //artifacts ---------------------------------------------
  def uploadArtifact(path: String, file: File): RestResult[HttpResponse] = {
    val pipeline = marathonSendReceive ~> responseResult
    val payload = MultipartFormData(Seq(BodyPart(file, "file")))
    result(pipeline(Post(s"$url/v2/artifacts$path", payload)), waitTime)
  }

  def getArtifact(path: String): RestResult[HttpResponse] = {
    val pipeline = marathonSendReceive ~> responseResult
    result(pipeline(Get(s"$url/v2/artifacts$path")), waitTime)
  }

  def deleteArtifact(path: String): RestResult[HttpResponse] = {
    val pipeline = marathonSendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/artifacts$path")), waitTime)
  }

  //leader ----------------------------------------------
  def leader(): RestResult[ITLeaderResult] = {
    result(leaderAsync(), waitTime)
  }

  def leaderAsync(): Future[RestResult[ITLeaderResult]] = {
    val pipeline = marathonSendReceive ~> read[ITLeaderResult]
    Retry("leader") { pipeline(Get(s"$url/v2/leader")) }
  }

  def abdicate(): RestResult[HttpResponse] = {
    val pipeline = marathonSendReceive ~> responseResult
    result(Retry("abdicate") { pipeline(Delete(s"$url/v2/leader")) }, waitTime)
  }

  //info --------------------------------------------------
  def info: RestResult[HttpResponse] = {
    val pipeline = marathonSendReceive ~> responseResult
    result(pipeline(Get(s"$url/v2/info")), waitTime)
  }

  //launch queue ------------------------------------------
  def launchQueue(): RestResult[ITLaunchQueue] = {
    val pipeline = marathonSendReceive ~> read[ITLaunchQueue]
    result(pipeline(Get(s"$url/v2/queue")), waitTime)
  }

  //resources -------------------------------------------

  def getPath(path: String): RestResult[HttpResponse] = {
    val pipeline = marathonSendReceive ~> responseResult
    result(pipeline(Get(s"$url$path")), waitTime)
  }
}

object MarathonFacade {
  def extractDeploymentIds(app: RestResult[App]): Seq[String] = {
    try {
      for (deployment <- (app.entityJson \ "deployments").as[JsArray].value)
        yield (deployment \ "id").as[String]
    } catch {
      case NonFatal(e) =>
        throw new RuntimeException(s"while parsing:\n${app.entityPrettyJsonString}", e)
    }
  }.toIndexedSeq
}
