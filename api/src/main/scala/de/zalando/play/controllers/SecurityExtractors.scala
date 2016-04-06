package de.zalando.play.controllers

import java.net.URLEncoder

import play.api.libs.json.JsValue
import play.api.mvc.{Result, ActionBuilder, Request, RequestHeader}
import play.api.mvc.Security.AuthenticatedRequest
import sun.misc.BASE64Decoder

import scala.concurrent.Future
import play.api.libs.iteratee.Execution.Implicits.trampoline

import scala.language.implicitConversions


/**
  * @author slasch 
  * @since 07.03.2016.
  *
  * Helper class for long authentication process
  * @param userinfo
  * @param onUnauthorized
  * @tparam U
  */
class FutureAuthenticatedBuilder[U](userinfo: RequestHeader => Future[Option[U]],
                              onUnauthorized: RequestHeader => Result)
  extends ActionBuilder[({ type R[A] = AuthenticatedRequest[A, U] })#R] {

  override def invokeBlock[A](request: Request[A], block: (AuthenticatedRequest[A, U]) => Future[Result]) =
    authenticate(request, block)

  def authenticate[A](request: Request[A], block: (AuthenticatedRequest[A, U]) => Future[Result]) = {
    userinfo(request).flatMap { userOpt =>
      userOpt.map { user =>
        block(new AuthenticatedRequest(user, request))
      } getOrElse {
        Future.successful(onUnauthorized(request))
      }
    }
  }
}


object SwaggerSecurityExtractors extends BasicAuthSecurityExtractor with OAuthResourceOwnerPasswordCredentialsFlow {
  private implicit def option2futureOption[User](o:Option[User]): Future[Option[User]] = Future.successful(o)

  def basicAuth[User >: Any]: RequestHeader => ((String, String) => User) => Future[Option[User]] =
    header => convertToUser => header.headers.get("Authorization").map { basicAuth =>
      decodeBasicAuth(basicAuth).map(p => convertToUser(p._1, p._2))
    }

  def headerApiKey[User >: Any]: String => RequestHeader => (String => User) => Future[Option[User]] =
    name => header => convertToUser => header.headers.get(name) map convertToUser

  def queryApiKey[User >: Any]: String => RequestHeader => (String => User) => Future[Option[User]] =
    name => header => convertToUser => header.queryString.get(name).flatMap(_.headOption) map convertToUser

  def oAuth[User >: Any]: Seq[String] => String => RequestHeader => (JsValue => User) => Future[Option[User]] =
    scopes => tokenUrl => header => convertToUser => {
    val futureResult = header.headers.get("Authorization").flatMap(decodeBearer).map { token: String =>
      checkOAuthToken(tokenUrl, token, scopes:_*)
    } match {
      case None => Future.successful(None)
      case Some(f) => f
    }
    futureResult.map(_.map(convertToUser))
  }

}

trait BasicAuthSecurityExtractor {
  private val basicSt = "basic "

  protected def decodeBasicAuth(auth: String): Option[(String, String)] = {
    lazy val basicReqSt = auth.substring(0, basicSt.length())
    lazy val basicAuthSt = auth.replaceFirst(basicReqSt, "")
    lazy val decoder = new BASE64Decoder() //BESE64Decoder is not thread safe
    lazy val decodedAuthSt = new String(decoder.decodeBuffer(basicAuthSt), "UTF-8")
    lazy val usernamePassword = decodedAuthSt.split(":")

    if (auth.length() < basicSt.length()) None
    else if (basicReqSt.toLowerCase() != basicSt) None
    else if (usernamePassword.length != 2) None
    else Some(usernamePassword.head, usernamePassword.last)
  }
}

trait OAuthCommon {
  private val bearer = "Bearer "

  protected def decodeBearer(auth: String): Option[String] =
    if (auth.length < bearer.length) None
    else if (auth.substring(0, bearer.length).toLowerCase != bearer) None
    else Some(auth.substring(bearer.length))
}
/**
  * This implementation implements token verification in following way:
  *
  * 1. If there is a ':token' part in the token url, it will be replaced by the url encoded token value
  *   then, the GET request with the resulting url is issued and response is parsed as JSON,
  *   as defined in https://tools.ietf.org/html/rfc7662
  *
  * 2. If no ':token' part present in the url, the POST request with Content-Type: application/x-www-form-urlencoded
  *   and token parameter performed and the result parsed as JSON. This behavior is as
  *   defined in https://tools.ietf.org/html/rfc7662
  */
trait OAuthResourceOwnerPasswordCredentialsFlow extends OAuthCommon {
  import play.api.libs.ws._
  import play.api.Play.current
  private val placeHolder = ":token"
  protected def checkOAuthToken(tokenUrl: String, token: String, requiredScopes: String*): Future[Option[JsValue]] = {
    val escapedToken = URLEncoder.encode(token, "UTF-8")
    val (method, url, body) =
      if (tokenUrl.contains(placeHolder)) ("GET", tokenUrl.replaceAllLiterally(placeHolder, escapedToken), "")
      else ("POST", tokenUrl, s"token=$escapedToken")
    val request = WS.url(url).withMethod(method).withFollowRedirects(true).
      withBody(InMemoryBody(body.getBytes)).withHeaders("Accept" -> "application/json")
    request.execute().map(_.json).map { json =>
      val active = (json \ "active").asOpt[Boolean].getOrElse(true)
      lazy val scope = (json \ "scope").asOpt[String]
      lazy val scopes = (json \ "scopes").asOpt[Array[String]]
      lazy val allScopes = (scope.map(_.split(' ')) orElse scopes).map(_.toSeq)
      val valid = active && allScopes.exists(s => s.intersect(requiredScopes) == scopes)
      if (valid) Some(json) else None
    }
  }
}
