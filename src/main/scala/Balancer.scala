import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.scaladsl._
import com.typesafe.config.ConfigFactory

import scala.collection.JavaConversions._

class BootedServer(val port:Int) extends LocalHttpServer with Core

object BalancerApp extends App{
  val config = ConfigFactory.load()

  val _localServerPorts = config.getIntList("local-servers.ports")
    .toIndexedSeq
    .map(_.intValue())
  val balancerInterface = config.getString("balancer.interface")
  val balancerPort = config.getInt("balancer.port")

  val servers = _localServerPorts.map(new BootedServer(_))
  val balancer = new Balancer with Core{
    override def localServerPorts = _localServerPorts
    override def serverUri = "/dictionaries/hello/suggestions?ngr=hond"
    override def interface = balancerInterface
    override def port = balancerPort
  }

  balancer.start()
  servers.foreach(_.start())
}

trait Balancer {
  this:Core =>

  def localServerPorts:Seq[Int]
  def serverUri:Uri
  def interface:String
  def port:Int
  
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

      val balance = b.add(Balance[HttpRequest](localServerPorts.length))
      val merge = b.add(Merge[HttpResponse](localServerPorts.length))

      //TODO dirty Try[HttpResponse] processing
      val connectionFlows = localServerPorts.map{ _port =>
        Http()
          .cachedHostConnectionPool[Int](host = "127.0.0.1", port = _port)
          .map(x => x._1.get)
      }

      val requestFlow = Flow[HttpRequest].map{_ =>
        (HttpRequest(uri = "/dictionaries/hello/suggestions?ngr=hond"), 0)
      }

      //TODO
      //There would be problems with request ordering. Here should be additional mapping between request and response.
      connectionFlows.map{ connectionFlow =>
        balance ~> requestFlow ~> connectionFlow ~> merge
      }

      (balance.in, merge.out)
    }

    Http().bind(
      interface = interface,
      port = port
    ).to(Sink.foreach { conn =>
      conn.flow.join(route(flow)).run()
    }).run()
  }
}