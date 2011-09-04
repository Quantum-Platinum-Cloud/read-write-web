package org.w3.readwriteweb

import javax.servlet._
import javax.servlet.http._
import unfiltered.request._
import unfiltered.response._
import unfiltered.jetty._

import java.io._
import scala.io.Source
import java.net.URL

import org.slf4j.{Logger, LoggerFactory}

import com.hp.hpl.jena.rdf.model._
import com.hp.hpl.jena.query._
import com.hp.hpl.jena.update._
import com.hp.hpl.jena.shared.JenaException
import Query.{QueryTypeSelect => SELECT, QueryTypeAsk => ASK,
              QueryTypeConstruct => CONSTRUCT, QueryTypeDescribe => DESCRIBE}

import scalaz._
import Scalaz._

import org.w3.readwriteweb.util._

class ReadWriteWeb(rm:ResourceManager) {
  
  val logger:Logger = LoggerFactory.getLogger(this.getClass)

  def isHTML(accepts:List[String]):Boolean = {
    val accept = accepts.headOption
    accept == Some("text/html") || accept == Some("application/xhtml+xml")
  }
  
  val read = unfiltered.filter.Planify {
    case req @ Path(path) if path startsWith (rm.basePath) => {
      val baseURI = req.underlying.getRequestURL.toString
      val r:Resource = rm.resource(new URL(baseURI))
      req match {
        case GET(_) & Accept(accepts) if isHTML(accepts) => {
          val source = Source.fromFile("src/main/resources/skin.html")("UTF-8")
          val body = source.getLines.mkString("\n")
          Ok ~> ViaSPARQL ~> ContentType("text/html") ~> ResponseString(body)
        }
        case GET(_) | HEAD(_) => {
          val response = for {
            model <- r.get() failMap { x => NotFound }
            encoding = RDFEncoding(req)
          } yield {
            req match {
              case GET(_) => Ok ~> ViaSPARQL ~> ContentType(encoding.toContentType) ~> ResponseModel(model, baseURI, encoding)
              case HEAD(_) => Ok ~> ViaSPARQL ~> ContentType(encoding.toContentType)
            }
          }
          response.fold(f => f, s => s)
        }
        case PUT(_) => {
          val response = for {
            bodyModel <- modelFromInputStream(Body.stream(req), baseURI) failMap { t => BadRequest ~> ResponseString(t.getStackTraceString) }
            _ <- r.save(bodyModel) failMap { t => InternalServerError ~> ResponseString(t.getStackTraceString) }
          } yield Created
          response.fold(f => f, s => s)
        }
        case POST(_) => {
          Post.parse(Body.stream(req), baseURI) match {
            case PostUnknown => {
              logger.info("Couldn't parse the request")
              BadRequest ~> ResponseString("You MUST provide valid content for either: SPARQL UPDATE, SPARQL Query, RDF/XML, TURTLE")
            }
            case PostUpdate(update) => {
              logger.info("SPARQL UPDATE:\n" + update.toString())
              val response =
                for {
                  model <- r.get() failMap { t => NotFound }
                  _ <- UpdateAction.execute(update, model).success
                  _ <- r.save(model) failMap { t =>  InternalServerError ~> ResponseString(t.getStackTraceString)}
                } yield Ok
              response.fold(f => f, s => s)
            }
            case PostRDF(diffModel) => {
              logger.info("RDF content:\n" + diffModel.toString())
              val response =
                for {
                  model <- r.get() failMap { t => NotFound }
                  _ <- model.add(diffModel).success
                  _ <- r.save(model) failMap { t =>  InternalServerError ~> ResponseString(t.getStackTraceString)}
                } yield Ok
              response.fold(f => f, s => s)
            }
            case PostQuery(query) => {
              logger.info("SPARQL Query:\n" + query.toString())
              lazy val encoding = RDFEncoding(req)
              val response =
                for {
                  model <- r.get() failMap { t => NotFound }
                } yield {
                  val qe:QueryExecution = QueryExecutionFactory.create(query, model)
                  query.getQueryType match {
                    case SELECT =>
                      Ok ~> ContentType("application/sparql-results+xml") ~> ResponseResultSet(qe.execSelect())
                    case ASK =>
                      Ok ~> ContentType("application/sparql-results+xml") ~> ResponseResultSet(qe.execAsk())
                    case CONSTRUCT => {
                      val result:Model = qe.execConstruct()
                      Ok ~> ContentType(encoding.toContentType) ~> ResponseModel(model, baseURI, encoding)
                    }
                    case DESCRIBE => {
                      val result:Model = qe.execDescribe()
                      Ok ~> ContentType(encoding.toContentType) ~> ResponseModel(model, baseURI, encoding)
                    }
                  }
                }
              response.fold(f => f, s => s)
            }
          }
        }
        case _ => MethodNotAllowed ~> Allow("GET", "PUT", "POST")
      }
    }

  }

}


object ReadWriteWebMain {

  val logger:Logger = LoggerFactory.getLogger(this.getClass)

  // regular Java main
  def main(args: Array[String]) {
    
    val argsList = args.toList
    
    val (port, baseDirectory, baseURL) = argsList match {
      case port :: directory :: base :: _ => (port.toInt, new File(directory), base)
      case _ => {
        println(
"""example usage:
    java -jar read-write-web.jar 8080 ~/WWW/2011/09 /2011/09 [-strict|-relax]

Options:
 -relax all resources potentially exist, meaning you get an empty RDF graph instead of a 404 (still experimental)
 -strict a GET on a resource will fail with a 404 (default mode if you omit it)
""")
        System.exit(1)
        null
      }
    }

    val mode =
      if (argsList contains "-relax") {
        logger.info("info: using experimental relax mode")
        AllResourcesAlreadyExist
      } else {
        ResourcesDontExistByDefault
      }
    
    if (! baseDirectory.exists) {
      println("%s does not exist" format (baseDirectory.getAbsolutePath))
      System.exit(2)
    }

    val filesystem = new Filesystem(baseDirectory, baseURL, lang="TURTLE")(mode)
    
    val app = new ReadWriteWeb(filesystem)

    // configures and launches a Jetty server
    unfiltered.jetty.Http(port).filter {
      // a jee Servlet filter that logs HTTP requests
      new Filter {
        def destroy():Unit = ()
        def doFilter(request:ServletRequest, response:ServletResponse, chain:FilterChain):Unit = {
          val r:HttpServletRequest = request.asInstanceOf[HttpServletRequest]
          val method = r.getMethod
          val uri = r.getRequestURI 
          logger.info("%s %s" format (method, uri))
          chain.doFilter(request, response)
        }
        def init(filterConfig:FilterConfig):Unit = ()
      }
    // Unfiltered filters
    }.context("/public"){ ctx:ContextBuilder =>
      ctx.resources(MyResourceManager.fromClasspath("public/").toURI.toURL)
    }.filter(app.read).run()
    
  }

}

