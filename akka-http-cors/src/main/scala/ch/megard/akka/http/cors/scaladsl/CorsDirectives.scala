package ch.megard.akka.http.cors.scaladsl

import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{HttpHeader, HttpMethod, HttpResponse, StatusCodes}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives._
import ch.megard.akka.http.cors.scaladsl.model.HttpHeaderRange
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings

import scala.collection.immutable.Seq

/**
  * Provides directives that implement the CORS mechanism, enabling cross origin requests.
  *
  * @see [[https://www.w3.org/TR/cors/ CORS W3C Recommendation]]
  * @see [[https://www.ietf.org/rfc/rfc6454.txt RFC 6454]]
  *
  */
trait CorsDirectives {

  import BasicDirectives._
  import CorsDirectives._
  import RouteDirectives._

  /**
    * Wraps its inner route with support for the CORS mechanism, enabling cross origin requests.
    *
    * In particular the recommendation written by the W3C in https://www.w3.org/TR/cors/ is
    * implemented by this directive.
    *
    * @param settings the settings used by the CORS filter
    */
  def cors(settings: CorsSettings = CorsSettings.defaultSettings): Directive0 = corsDecorate(settings).map(_ ⇒ ())

  /**
    * Wraps its inner route with support for the CORS mechanism, enabling cross origin requests.
    * Provides to the inner route an object that indicates if the current request is a valid CORS
    * actual request or is outside the scope of the specification.
    *
    * In particular the recommendation written by the W3C in https://www.w3.org/TR/cors/ is
    * implemented by this directive.
    *
    * @param settings the settings used by the CORS filter
    */
  def corsDecorate(settings: CorsSettings = CorsSettings.defaultSettings): Directive1[CorsDecorate] = {
    import settings._

    def accessControlExposeHeaders: Option[`Access-Control-Expose-Headers`] = {
      if (exposedHeaders.nonEmpty) Some(`Access-Control-Expose-Headers`(exposedHeaders))
      else None
    }

    def accessControlAllowCredentials: Option[`Access-Control-Allow-Credentials`] = {
      if (allowCredentials) Some(`Access-Control-Allow-Credentials`(true))
      else None
    }

    def accessControlMaxAge: Option[`Access-Control-Max-Age`] = {
      maxAge.map(`Access-Control-Max-Age`.apply)
    }

    def accessControlAllowHeaders(requestHeaders: Seq[String]): Option[`Access-Control-Allow-Headers`] = allowedHeaders match {
      case HttpHeaderRange.Default(headers) ⇒ Some(`Access-Control-Allow-Headers`(headers))
      case HttpHeaderRange.* if requestHeaders.nonEmpty ⇒ Some(`Access-Control-Allow-Headers`(requestHeaders))
      case _ ⇒ None
    }

    def accessControlAllowMethods = {
      `Access-Control-Allow-Methods`(allowedMethods)
    }

    def accessControlAllowOrigin(origins: Seq[HttpOrigin]): `Access-Control-Allow-Origin` = {
      if (allowedOrigins == HttpOriginRange.* && !allowCredentials) {
        `Access-Control-Allow-Origin`.*
      } else {
        `Access-Control-Allow-Origin`.forRange(HttpOriginRange.Default(origins))
      }
    }

    /** Return the invalid origins, or `None` if one is valid. */
    def validateOrigins(origins: Seq[HttpOrigin]): Option[CorsRejection.Cause] =
      if (allowedOrigins == HttpOriginRange.* || origins.exists(allowedOrigins.matches)) {
        None
      } else {
        Some(CorsRejection.InvalidOrigin(origins))
      }

    /** Return the method if invalid, `None` otherwise. */
    def validateMethod(method: HttpMethod): Option[CorsRejection.Cause] =
      if (allowedMethods.contains(method)) {
        None
      } else {
        Some(CorsRejection.InvalidMethod(method))
      }

    /** Return the list of invalid headers, or `None` if they are all valid. */
    def validateHeaders(headers: Seq[String]): Option[CorsRejection.Cause] = {
      val invalidHeaders = headers.filterNot(allowedHeaders.matches)
      if (invalidHeaders.isEmpty) {
        None
      } else {
        Some(CorsRejection.InvalidHeaders(invalidHeaders))
      }
    }

    extractRequest.flatMap { request ⇒
      import request._

      (method, header[Origin].map(_.origins), header[`Access-Control-Request-Method`].map(_.method)) match {
        case (OPTIONS, Some(origins), Some(requestMethod)) if origins.lengthCompare(1) == 0 ⇒
          // Case 1: pre-flight CORS request

          val headers = header[`Access-Control-Request-Headers`].map(_.headers).getOrElse(Seq.empty)

          def completePreflight = {
            val responseHeaders = Seq(accessControlAllowOrigin(origins), accessControlAllowMethods) ++
              accessControlAllowHeaders(headers) ++ accessControlMaxAge ++ accessControlAllowCredentials
            complete(HttpResponse(StatusCodes.OK, responseHeaders))
          }

          List(validateOrigins(origins), validateMethod(requestMethod), validateHeaders(headers))
            .collectFirst { case Some(cause) => CorsRejection(cause) }
            .fold(completePreflight)(reject(_))

        case (_, Some(origins), None) ⇒
          // Case 2: simple/actual CORS request

          val decorate: CorsDecorate = CorsDecorate.CorsRequest(origins)
          val cleanAndAddHeaders: Seq[HttpHeader] => Seq[HttpHeader] = { oldHeaders =>
            val filteredHeaders = oldHeaders.filterNot(h => CorsDirectives.headersToClean.exists(h.is))
            List(accessControlAllowOrigin(origins)) ++
              accessControlExposeHeaders ++
              accessControlAllowCredentials ++
              filteredHeaders
          }

          validateOrigins(origins) match {
            case None ⇒
              mapResponseHeaders(cleanAndAddHeaders) & provide(decorate)
            case Some(cause) ⇒
              reject(CorsRejection(cause))
          }

        case _ if allowGenericHttpRequests ⇒
          // Case 3a: not a valid CORS request, but allowed

          provide(CorsDecorate.NotCorsRequest)

        case _ ⇒
          // Case 3b: not a valid CORS request, forbidden

          reject(CorsRejection(CorsRejection.Malformed))
      }
    }
  }

}

object CorsDirectives extends CorsDirectives {

  import RouteDirectives._

  private val headersToClean: List[String] = List(
    `Access-Control-Allow-Origin`,
    `Access-Control-Expose-Headers`,
    `Access-Control-Allow-Credentials`,
    `Access-Control-Allow-Methods`,
    `Access-Control-Allow-Headers`,
    `Access-Control-Max-Age`
  ).map(_.lowercaseName)

  def corsRejectionHandler = RejectionHandler.newBuilder().handle {
    case CorsRejection(cause) ⇒
      val message = cause match {
        case CorsRejection.Malformed ⇒
          "malformed request"
        case CorsRejection.InvalidOrigin(origins) ⇒
          val listOrNull = if (origins.isEmpty) "null" else origins.mkString(" ")
          s"invalid origin '$listOrNull'"
        case CorsRejection.InvalidMethod(method) ⇒
          s"invalid method '${method.value}'"
        case CorsRejection.InvalidHeaders(headers) ⇒
          s"invalid headers '${headers.mkString(" ")}'"
      }
      complete((StatusCodes.BadRequest, s"CORS: $message"))
  }.result()

  sealed abstract class CorsDecorate {
    def isCorsRequest: Boolean
  }

  object CorsDecorate {

    case class CorsRequest(origins: Seq[HttpOrigin]) extends CorsDecorate {
      def isCorsRequest = true
    }

    case object NotCorsRequest extends CorsDecorate {
      def isCorsRequest = false
    }

  }

}
