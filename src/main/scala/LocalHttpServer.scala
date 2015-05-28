import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.scaladsl.Sink

trait LocalHttpServer {
  this: Core =>

  val _port:Int

  def start() = {
    val route: Route = {
      path("dictionaries" / Segment / "suggestions"){ dictionaryId =>
        get{
          parameters("ngr"){ ngr =>
            complete("response")
          }
        }
      }
    }

    Http().bind(
      interface = "127.0.0.1",
      port = _port
    ).to(Sink.foreach { conn =>
      conn.flow.join(route).run()
    }).run()
  }
}
