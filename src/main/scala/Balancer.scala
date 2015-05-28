import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{Uri, HttpResponse, HttpRequest}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl._
import com.typesafe.config.{ConfigFactory, Config}

import scala.concurrent.ExecutionContext

trait Core  {
  implicit val system: ActorSystem = ActorSystem("http-balancer")
  implicit val materializer: ActorFlowMaterializer = ActorFlowMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
}

object BalancerApp extends App{
  val balancer = new Balancer with Core{
    override def config: Config = ConfigFactory.load()
  }

  balancer.start()
}

trait Balancer {
  this:Core =>

  def config: Config
  
  def serverUris = Seq(Uri("http://localhost:4051/dictionaries/hello/suggestions?ngr=hond"),
                       Uri("http://localhost:4052/dictionaries/hello/suggestions?ngr=hond"))
            
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
      //There would be problems with request ordering here. Here should be additional mapping between request and response.
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