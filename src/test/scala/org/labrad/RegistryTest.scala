package org.labrad

import org.labrad.annotations._
import org.labrad.data._
import org.scalatest.FunSuite
import org.scalatest.concurrent.AsyncAssertions
import scala.collection._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.time.SpanSugar._

class RegistryTest extends FunSuite with AsyncAssertions {

  import TestUtils._

  test("registry can store and retrieve arbitrary data") {
    withManager { (host, port, password) =>
      withClient(host, port, password) { c =>
        await(c.send("Registry", "mkdir" -> Str("test"), "cd" -> Str("test")))
        try {
          for (i <- 0 until 1000) {
            val tpe = Hydrant.randomType
            val data = Hydrant.randomData(tpe)
            await(c.send("Registry", "set" -> Cluster(Str("a"), data)))
            val resp = await(c.send("Registry", "get" -> Str("a")))(0)
            await(c.send("Registry", "del" -> Str("a")))
            assert(resp == data, s"${resp} (type=${resp.t}) is not equal to ${data} (type=${data.t})")
          }
        } finally {
          await(c.send("Registry", "cd" -> Str(".."), "rmdir" -> Str("test")))
        }
      }
    }
  }

  test("registry can deal with unicode and strange characters in directory names") {
    withManager { (host, port, password) =>
      withClient(host, port, password) { c =>
        val dir = "<\u03C0|\u03C1>??+*"
        await(c.send("Registry", "mkdir" -> Str(dir)))
        val (dirs, _) = await(c.send("Registry", "dir" -> Data.NONE))(0).get[(Seq[String], Seq[String])]
        assert(dirs contains dir)
        await(c.send("Registry", "cd" -> Str(dir)))
      }
    }
  }

  test("registry can deal with unicode and strange characters in key names") {
    withManager { (host, port, password) =>
      withClient(host, port, password) { c =>
        val key = "<\u03C0|\u03C1>??+*"
        val data = Str("Hello!")
        await(c.send("Registry", "set" -> Cluster(Str(key), Str("Hello!"))))
        val (_, keys) = await(c.send("Registry", "dir" -> Data.NONE))(0).get[(Seq[String], Seq[String])]
        assert(keys contains key)
        val result = await(c.send("Registry", "get" -> Str(key)))(0)
        assert(result == data)
      }
    }
  }

  test("registry sends message when key is created") {
    withManager { (host, port, password) =>
      withClient(host, port, password) { c =>

        val w = new Waiter

        val msgId = 1234

        c.addMessageListener {
          case Message(src, ctx, `msgId`, data) =>
            w { assert(data == Cluster(Str("a"), Bool(false), Bool(true))) }
            w.dismiss
        }

        await(c.send("Registry", "mkdir" -> Str("test"), "cd" -> Str("test")))
        await(c.send("Registry", "Notify On Change" -> Cluster(UInt(msgId), Bool(true))))
        await(c.send("Registry", "set" -> Cluster(Str("a"), Str("test"))))
        w.await(timeout(10.seconds))
      }
    }
  }

  test("registry sends message when key is changed") {
    withManager { (host, port, password) =>
      withClient(host, port, password) { c =>

        val w = new Waiter

        val msgId = 1234

        c.addMessageListener {
          case Message(src, ctx, `msgId`, data) =>
            w { assert(data == Cluster(Str("a"), Bool(false), Bool(true))) }
            w.dismiss
        }

        await(c.send("Registry", "mkdir" -> Str("test"), "cd" -> Str("test")))
        await(c.send("Registry", "set" -> Cluster(Str("a"), Str("first"))))
        await(c.send("Registry", "Notify On Change" -> Cluster(UInt(msgId), Bool(true))))
        await(c.send("Registry", "set" -> Cluster(Str("a"), Str("second"))))
        w.await
      }
    }
  }

  test("registry sends message when key is deleted") {
    withManager { (host, port, password) =>
      withClient(host, port, password) { c =>

        val w = new Waiter

        val msgId = 1234

        c.addMessageListener {
          case Message(src, ctx, `msgId`, data) =>
            w { assert(data == Cluster(Str("a"), Bool(false), Bool(false))) }
            w.dismiss
        }

        await(c.send("Registry", "mkdir" -> Str("test"), "cd" -> Str("test")))
        await(c.send("Registry", "set" -> Cluster(Str("a"), Str("first"))))
        await(c.send("Registry", "Notify On Change" -> Cluster(UInt(msgId), Bool(true))))
        await(c.send("Registry", "del" -> Str("a")))
        w.await
      }
    }
  }
}

object RegistryTest {

  import TestUtils._

  def main(args: Array[String]): Unit = {
    val (host, port) = args match {
      case Array(host) =>
        host.split(":") match {
          case Array(host, port) => host -> port.toInt
          case Array(host) => host -> 7682
        }

      case Array() =>
        "localhost" -> 7682
    }

    withClient(host, port, password = Array()) { c =>
      val reg = new RegistryServerProxy(c)
      val (dirs, keys) = await(reg.dir())
      if (!dirs.contains("test")) {
        await(reg.mkDir("test"))
      }
      await(reg.cd("test"))

      def mkdir(level: Int = 0): Unit = {
        if (level < 3) {
          for (i <- 0 until 3) {
            val dir = s"dir$i"
            await(reg.mkDir(dir))
            await(reg.cd(dir))
            mkdir(level + 1)
            await(reg.cd(".."))
          }
        }
        for (i <- 0 until 10) {
          val key = f"key$i%03d"
          val tpe = Hydrant.randomType
          val data = Hydrant.randomData(tpe)
          await(reg.set(key, data))
          val resp = await(reg.get(key))
          assert(resp ~== data, s"${resp} (type=${resp.t}) is not equal to ${data} (type=${data.t})")
        }
      }
      mkdir()
    }
  }
}
