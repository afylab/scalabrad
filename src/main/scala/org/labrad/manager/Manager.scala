package org.labrad.manager

import java.io.File
import java.net.URI
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import org.labrad.annotations._
import org.labrad.data._
import org.labrad.errors._
import org.labrad.registry._
import org.labrad.util._
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


trait AuthService {
  def authenticate(challenge: Array[Byte], response: Array[Byte]): Boolean
}

class AuthServiceImpl(password: Array[Char]) extends AuthService {
  def authenticate(challenge: Array[Byte], response: Array[Byte]): Boolean = {
    val md = MessageDigest.getInstance("MD5")
    md.update(challenge)
    md.update(UTF_8.encode(CharBuffer.wrap(password)))
    val expected = md.digest
    var same = expected.length == response.length
    for ((a, b) <- expected zip response) same = same & (a == b)
    same
  }
}


class CentralNode(port: Int, password: Array[Char], store: RegistryStore) extends Logging {
  // start services
  val tracker = new StatsTrackerImpl
  val hub: Hub = new HubImpl(tracker, () => messager)
  val messager: Messager = new MessagerImpl(hub, tracker)
  val auth: AuthService = new AuthServiceImpl(password)

  // Manager gets id 1L
  tracker.connectServer(Manager.ID, Manager.NAME)

  { // Registry gets id 2L
    val name = "Registry"
    val id = hub.allocateServerId(name)
    val server = new Registry(id, name, store, hub, tracker)
    hub.connectServer(id, name, server)
  }

  // start listening for incoming network connections
  val listener = new Listener(auth, hub, tracker, messager, port)

  def stop() {
    listener.stop()
  }
}


object Manager extends Logging {
  val ID = 1L
  val NAME = "Manager"
  val DOC = "Provides basic support for all labrad connections, including discovery of other servers and lookup of metadata about them."

  // setting ids
  val SERVERS = 1L
  val SETTINGS = 2L
  val LOOKUP = 3L

  // named messages
  case class Connect(id: Long) extends Message {
    def name: String = "Connect"
    def data: Data = UInt(id)
  }

  case class Disconnect(id: Long) extends Message {
    def name: String = "Disconnect"
    def data: Data = UInt(id)
  }

  case class ConnectServer(id: Long, serverName: String) extends Message {
    def name: String = "Server Connect"
    def data: Data = Cluster(UInt(id), Str(serverName))
  }

  case class DisconnectServer(id: Long, serverName: String) extends Message {
    def name: String = "Server Disconnect"
    def data: Data = Cluster(UInt(id), Str(serverName))
  }

  case class ExpireAll(id: Long) extends Message {
    def name: String = "Expire All"
    def data: Data = UInt(id)
  }

  case class ExpireContext(ctx: Context) extends Message {
    def name: String = "Expire Context"
    def data: Data = ctx.toData
  }

  // helpers for dealing with paths
  implicit class PathString(path: String) {
    def / (file: String): File = new File(path, file)
  }

  implicit class PathFile(path: File) {
    def / (file: String): File = new File(path, file)
  }

  def main(args: Array[String]) {
    val options = Util.parseArgs(args, Seq("port", "password", "registry"))

    val port = options.get("port").orElse(sys.env.get("LABRADPORT")).map(_.toInt).getOrElse(7682)
    val password = options.get("password").orElse(sys.env.get("LABRADPASSWORD")).getOrElse("").toCharArray
    val registryUri = options.get("registry").orElse(sys.env.get("LABRADREGISTRY")).map(new URI(_)).getOrElse {
      (sys.props("user.home") / ".labrad" / "registry.sqlite").toURI
    }

    val store = registryUri.getScheme match {
      case "file" =>
        val registry = new File(registryUri)
        log.info(s"registry location: $registry")
        val dir = registry.getAbsoluteFile.getParentFile
        if (!dir.exists) {
          val ok = dir.mkdirs()
          if (!ok) sys.error(s"failed to create registry directory: $dir")
        }
        SQLiteStore(registry)

      case "labrad" =>
        val remoteHost = registryUri.getHost
        val remotePort = registryUri.getPort
        val remotePassword = registryUri.getUserInfo match {
          case null => password
          case info => info.split(":") match {
            case Array() => password
            case Array(pw) => pw.toCharArray
            case Array(u, pw) => pw.toCharArray
          }
        }
        log.info(s"remote registry location: $remoteHost:$remotePort")
        RemoteStore(remoteHost, remotePort, remotePassword)

      case scheme =>
        sys.error(s"unknown scheme for registry uri: $scheme. must use 'file' or 'labrad'")
    }

    val centralNode = new CentralNode(port, password, store)

    @tailrec def enterPressed(): Boolean =
      System.in.available > 0 && (System.in.read() == '\n'.toInt || enterPressed())

    var done = false
    while (!done) {
      Thread.sleep(100)
      if (enterPressed()) done = true
    }
    centralNode.stop()
  }
}
