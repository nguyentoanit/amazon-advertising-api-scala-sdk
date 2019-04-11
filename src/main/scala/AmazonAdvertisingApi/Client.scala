package AmazonAdvertisingApi

import scalaj.http._
import play.api.libs.json._
import java.net.URL

class Client (clientId: String, clientSecret: String, refreshToken: String, region: Region, version: String, sandbox: Boolean = false) {
  var accessToken: String = ""
  var profileId: String = ""
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
    val request = this.buildRequest(POST, url, headers, body).asString
    val response: JsValue = Json.parse(request.body)
    (response \ "access_token").asOpt[String] match {
      case Some(ac) => this.accessToken = ac
      case None =>
        val error = (response \ "error").asOpt[String].getOrElse("Error Description")
        val errorDescription = (response \ "error_description").asOpt[String].getOrElse("Not Found!")
        throw new Exception(s"$error: $errorDescription")
    }
  }

  def requestReport(reportType: String, data: JsValue): HttpRequest = this._operation(reportType + "/report", POST, data)

  def getReport(reportId: String): URL = {
    val request = this._operation(s"reports/$reportId").asString
    val response: JsValue = Json.parse(request.body)
    (response \ "status").asOpt[String] match {
      case Some(status) if status == "SUCCESS" => this._download(s"reports/$reportId/download")
      case Some(status) if status == "IN_PROGRESS" =>
        // Pause 5 seconds before check status again
        Thread.sleep(5000)
        this.getReport(reportId)
      case Some(status) if status == "FAILURE" =>
        val statusDetails = (response \ "statusDetails").asOpt[String].getOrElse("Description is Not Found!")
        throw new Exception(s"$status: $statusDetails")
      case None =>
        val error = (response \ "error").asOpt[String].getOrElse("Error")
        val errorDescription = (response \ "error_description").asOpt[String].getOrElse("Description is Not Found!")
        throw new Exception(s"$error: $errorDescription")
    }
  }

  private def _download(path: String): URL = {
    val request = this._operation(path).asString
    val downloadLink: String = request.header("Location").get
    new URL(downloadLink)
  }

  private def _operation(path: String, method: HTTPMethod = GET, body: JsValue = Json.obj()): HttpRequest = {
    var headers: Seq[(String, String)] = Seq(
      "Content-Type" -> "application/json",
      "Authorization" -> s"Bearer ${this.accessToken}",
      "Amazon-Advertising-API-ClientId" -> this.clientId
    )

    if (!this.profileId.trim.isEmpty) headers = headers :+ ("Amazon-Advertising-API-Scope" -> this.profileId)
    else throw new Exception("Profile ID is not found!")

    val url = new URL(this.domain + "/" + this.version + "/" + path)
    this.buildRequest(method, url, headers, body)
  }
}

object Client {
  def apply(config: Config): Client = new Client(config.clientId, config.clientSecret, config.refreshToken, config.region, config.version, config.sandbox)
}