package mesosphere.marathon
package stream

import java.net.URI
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes
import akka.stream.Materializer
import akka.stream.alpakka.s3.acl.CannedAcl
import akka.stream.alpakka.s3.auth.AWSCredentials
import akka.stream.alpakka.s3.impl.MetaHeaders
import akka.stream.alpakka.s3.scaladsl.S3Client
import akka.stream.scaladsl.{ FileIO, Source, Sink => ScalaSink }
import akka.util.ByteString
import akka.{ Done, NotUsed }
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.{ ExecutionContext, Future }

/**
  * UriIO provides sources from and sinks to an URI.
  * It supports different providers, so multiple schemes are supported.
  *
  * Provided schemes:
  *
  * File
  * Example: {{{file:///path/to/file}}}
  * This will read/write from a local file under a given path
  *
  *
  * S3
  * Example: {{{s3://bucket.name/path/in/bucket?region=us=west-1&aws_access_key_id=XXX&aws_secret_access_key=jdhfdhjfg}}}
  * This will read/write from an S3 bucket in the specified path
  *
  */
object UriIO extends StrictLogging {

  /**
    * Source that reads from the specified URI.
    * @param uri the uri to read from
    * @return A source for reading the specified uri.
    */
  def reader(uri: URI)(implicit actorSystem: ActorSystem, materializer: Materializer): Source[ByteString, NotUsed] = {
    uri.getScheme match {
      case "file" =>
        FileIO
          .fromPath(Paths.get(uri.getPath))
          .mapMaterializedValue(_ => NotUsed)
      case "s3" =>
        s3Client(uri)
          .download(uri.getHost, uri.getPath.substring(1))
      case unknown => throw new RuntimeException(s"Scheme not supported: $unknown")
    }

  }

  /**
    * Sink that can write to the defined URI.
    * @param uri the URI to write to.
    * @return the sink that can write to the defined URI.
    */
  def writer(uri: URI)(implicit actorSystem: ActorSystem, materializer: Materializer, ec: ExecutionContext): ScalaSink[ByteString, Future[Done]] = {
    uri.getScheme match {
      case "file" =>
        FileIO
          .toPath(Paths.get(uri.getPath))
          .mapMaterializedValue(_.map(_ => Done))
      case "s3" =>
        logger.info(s"s3location: bucket:${uri.getHost}, path:${uri.getPath}")

        s3Client(uri)
          .multipartUpload(
            bucket = uri.getHost,
            key = uri.getPath.substring(1),
            metaHeaders = MetaHeaders(Map.empty),
            contentType = ContentTypes.`application/octet-stream`,
            cannedAcl = CannedAcl.BucketOwnerRead)
          .mapMaterializedValue(_.map(_ => Done))
      case unknown => throw new RuntimeException(s"Scheme not supported: $unknown")
    }
  }

  /**
    * Indicates, if the given uri is valid.
    * @param uri the uri to validate
    * @return true if this URI is valid, otherwise false.
    */
  def isValid(uri: URI): Boolean = {
    def nonEmpty(nullable: String): Boolean = nullable != null && nullable.nonEmpty
    uri.getScheme match {
      case "file" if nonEmpty(uri.getPath) && uri.getPath.length > 1 => true
      case "s3" if nonEmpty(uri.getHost) && nonEmpty(uri.getPath) => true
      case _ => false
    }
  }

  private[this] def s3Client(uri: URI)(implicit actorSystem: ActorSystem, materializer: Materializer): S3Client = {
    val params = parseParams(uri)
    val region = params.getOrElse("region", "us-east-1")
    val accessKey = params.getOrElse("access_key", "")
    val accessSecret = params.getOrElse("secret_key", "")
    logger.info(s"S3 settings region: $region accessKey:$accessKey accessSecret:$accessSecret")
    val credentials = AWSCredentials(accessKey, accessSecret)
    new S3Client(credentials, region)
  }

  private[this] def parseParams(uri: URI): Map[String, String] = {
    Option(uri.getQuery).getOrElse("").split("&").collect { case QueryParam(k, v) => k -> v }(collection.breakOut)
  }

  private[this] object QueryParam {
    def unapply(str: String): Option[(String, String)] = str.split("=") match {
      case Array(key: String, value: String) => Some(key -> value)
      case _ => None
    }
  }
}
