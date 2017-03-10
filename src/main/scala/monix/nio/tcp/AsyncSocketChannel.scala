/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.nio.tcp

import java.net.{ InetSocketAddress, StandardSocketOptions }
import java.nio.ByteBuffer
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{ AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler }
import java.util.concurrent.TimeUnit

import monix.eval.Callback
import monix.execution.Scheduler
import monix.nio.internal.ExecutorServiceWrapper

import scala.util.control.NonFatal

abstract class AsyncSocketChannel extends AutoCloseable {
  // TODO documentation and complete API
  def socketAddress: InetSocketAddress
  def closeWhenDone: Boolean
  def close()
  def connect(callback: Callback[Void])
  def read(dst: ByteBuffer, callback: Callback[Int])
  def write(src: ByteBuffer, callback: Callback[Int])
}

object AsyncSocketChannel {
  // TODO documentation and complete API

  def apply(to: InetSocketAddress, closeWhenDone: Boolean = true)(implicit s: Scheduler): AsyncSocketChannel = {
    NewIOImplementation(to, closeWhenDone)
  }

  private final case class NewIOImplementation(
      to: InetSocketAddress,
      closeWhenDone: Boolean,
      reuseAddress: Boolean = true,
      sendBufferSize: Int = 256 * 1024,
      receiveBufferSize: Int = 256 * 1024,
      keepAlive: Boolean = false,
      noDelay: Boolean = false
  )(implicit s: Scheduler) extends AsyncSocketChannel {

    private[this] lazy val asyncSocketChannel: Either[Throwable, AsynchronousSocketChannel] =
      try {
        val ag = AsynchronousChannelGroup.withThreadPool(ExecutorServiceWrapper(s))
        val ch = AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(ag)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
        ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sendBufferSize)
        ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
        ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
        Right(ch)
      } catch {
        case NonFatal(exc) =>
          s.reportFailure(exc)
          Left(exc)
      }

    override def socketAddress: InetSocketAddress = to

    override def close(): Unit = {
      asyncSocketChannel.fold(_ => (), c => c.close())
    }

    override def connect(callback: Callback[Void]): Unit = {
      val handler = new CompletionHandler[Void, Null] {
        override def completed(result: Void, attachment: Null) = {
          callback.onSuccess(result)
        }
        override def failed(exc: Throwable, attachment: Null) = exc match {
          case _: java.nio.channels.AsynchronousCloseException => ()
          case _ => callback.onError(exc)
        }
      }
      asyncSocketChannel.fold(_ => (), c => c.connect(to, null, handler))
    }

    override def read(dst: ByteBuffer, callback: Callback[Int]): Unit = {
      val handler = new CompletionHandler[Integer, Null] {
        override def completed(result: Integer, attachment: Null) = {
          callback.onSuccess(result)
        }
        override def failed(exc: Throwable, attachment: Null) = exc match {
          case _: java.nio.channels.AsynchronousCloseException => ()
          case _ => callback.onError(exc)
        }
      }

      asyncSocketChannel.fold(_ => (), { c =>
        c.read(dst, 0l, TimeUnit.MILLISECONDS, null, handler)
      })
    }

    override def write(src: ByteBuffer, callback: Callback[Int]): Unit = {
      val handler = new CompletionHandler[Integer, Null] {
        override def completed(result: Integer, attachment: Null) = {
          callback.onSuccess(result)
        }
        override def failed(exc: Throwable, attachment: Null) = exc match {
          case _: java.nio.channels.AsynchronousCloseException => ()
          case _ => callback.onError(exc)
        }
      }

      asyncSocketChannel.fold(_ => (), { c =>
        c.write(src, 0l, TimeUnit.MILLISECONDS, null, handler)
      })
    }
  }
}
