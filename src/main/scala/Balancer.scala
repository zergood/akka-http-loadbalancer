import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.scaladsl._


class BootedServer(val _port:Int) extends LocalHttpServer with Core

object BalancerApp extends App{
  val localServerPorts = Seq(10002, 10003)
  val servers = localServerPorts.map(new BootedServer(_))

  val localServerUris = localServerPorts.map(port => Uri(s"http://localhost:${port}/dictionaries/hello/suggestions?ngr=hond"))
  
  val balancer = new Balancer with Core{
    override def serverUris = localServerUris
  }

  balancer.start()
  servers.foreach(_.start())
}

trait Balancer {
  this:Core =>

  def serverUris:Seq[Uri] 
            
  def route(flow:Flow[HttpRequest, HttpResponse, Unit]): Route = {
    path("dictionaries" / Segment / "suggestions"){ dictionaryId =>
      get{
        parameters("ngr"){ ngr =>
          ctx =>
            Source.single(ctx.request)
              .via(flow)
              .runWith(Sink.head)
              .flatMap(x => ctx.complete(x))
        }
      }
    }
  }

  def start() = {
    val flow = Flow() { implicit b =>
      import akka.stream.scaladsl.FlowGraph.Implicits._

      val balance = b.add(Balance[HttpRequest](serverUris.length))
      val merge = b.add(Merge[HttpResponse](serverUris.length))

      //TODO dirty Try[HttpResponse] processing
      val connectionFlow = Http().superPool[Int]().map(x => x._1.get)

      //TODO
      //There would be problems with request ordering. Here should be additional mapping between request and response.
      serverUris.map { _uri =>
        val requestFlow = Flow[HttpRequest].map(_ => (HttpRequest(uri = _uri), 0))
        
        balance ~> requestFlow ~> connectionFlow ~> merge
      }

      (balance.in, merge.out)
    }

    Http().bind(
      interface = "127.0.0.1",
      port = 3535
    ).to(Sink.foreach { conn =>
      conn.flow.join(route(flow)).run()
    }).run()
  }
}