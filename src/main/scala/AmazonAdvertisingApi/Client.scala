package AmazonAdvertisingApi

import scalaj.http._
import play.api.libs.json._
import java.net.URL

import AmazonAdvertisingApi.exceptions.{ReportException, JsonParseException}
import javax.naming.AuthenticationException
import scala.util.{Try, Success, Failure}

class Client (clientId: String, clientSecret: String, refreshToken: String, region: Region, version: String, sandbox: Boolean = false) {
  var accessToken: String = ""
  val domain: String = if (sandbox) this.region.sandbox.toString else this.region.production.toString

  private def buildRequest(method: HTTPMethod, url: URL, headers: Seq[(String, String)], body: JsValue): HttpRequest = {
    val request = Http(url.toString).headers(headers)
    method match {
      case GET => request
      case POST => request.postData(Json.stringify(body))
    }
  }

  def doRefreshToken = {
    val headers = Seq(
      "Content-Type" -> "application/json"
    )
    val body: JsValue = Json.obj(
      "grant_type" -> "refresh_token",
      "refresh_token" -> this.refreshToken,
      "client_id" -> this.clientId,
      "client_secret" -> this.clientSecret
    )
    val url: URL = this.region.tokenUrl
    val request: HttpResponse[String] = this.buildRequest(POST, url, headers, body).asString

    Try(Json.parse(request.body)) match {
      case Success(response) =>
        (response \ "access_token").asOpt[String] match {
          case Some(aT) => this.accessToken = aT
          case None =>
            val error = (response \ "error").asOpt[String].getOrElse("Error Description")
            val errorDescription = (response \ "error_description").asOpt[String].getOrElse("Not Found!")
            throw new AuthenticationException(s"$error: $errorDescription")
        }
      case Failure(_) => throw new JsonParseException(s"Cannot Json.parse: ${request.body}")
    }
  }

  def requestReport(reportType: String, profileId: String, data: JsValue): HttpRequest = this._operation(reportType + "/report", profileId, POST, data)

  def getReportStatus(reportId: String, profileId: String): ReportStatus = {
    val request = this._operation(s"reports/$reportId", profileId).asString
    Try(Json.parse(request.body)) match {
      case Success(response) =>
        val statusDetails: String = (response \ "statusDetails").asOpt[String].getOrElse("Description is Not Found!")
        (response \ "status").asOpt[String] match {
          case Some(status) if status == "SUCCESS" => ReportSuccess(reportId, status, statusDetails)
          case Some(status) if status == "IN_PROGRESS" => ReportInProgress(reportId, status, statusDetails)
          case Some(status) if status == "FAILURE" => ReportFailure(reportId, status, statusDetails)
          case None =>
            val error = (response \ "code").asOpt[String].getOrElse("Error")
            val details = (response \ "details").asOpt[String].getOrElse("Description is Not Found!")
            throw new ReportException(s"$error: $details")
        }
      case Failure(_) => throw new JsonParseException(s"Cannot Json.parse: ${request.body}")
    }

  }

  def getReportURL(path: String, profileId: String): URL = {
    val request = this._operation(path, profileId).asString
    request.header("Location") match {
      case Some(value) => new URL(value)
      case None => {
        Try(Json.parse(request.body)) match {
          case Success(response) => {
            val error = (response \ "code").asOpt[String].getOrElse("Error")
            val details = (response \ "details").asOpt[String].getOrElse("Description is Not Found!")
            throw new ReportException(s"$error: $details")
          }
          case Failure(_) => throw new JsonParseException(s"Cannot Json.parse: ${request.body}")
        }
      }
    }
  }

  def listProfiles(): HttpRequest = this._operation("profiles")

  private def _operation(path: String, profileId: String = "", method: HTTPMethod = GET, body: JsValue = Json.obj()): HttpRequest = {
    var headers: Seq[(String, String)] = Seq(
      "Content-Type" -> "application/json",
      "Authorization" -> s"Bearer ${this.accessToken}",
      "Amazon-Advertising-API-ClientId" -> this.clientId
    )

    if (!profileId.trim.isEmpty) headers = headers :+ ("Amazon-Advertising-API-Scope" -> profileId)

    val url = new URL(this.domain + "/" + this.version + "/" + path)
    this.buildRequest(method, url, headers, body)
  }
}

object Client {
  def apply(config: Config): Client = new Client(config.clientId, config.clientSecret, config.refreshToken, config.region, config.version, config.sandbox)
}