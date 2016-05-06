/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.worker

import java.io.{File, FileOutputStream, InputStream, IOException}
import java.lang.System._

import scala.collection.JavaConversions._
import scala.collection.Map

import org.apache.spark.Logging
import org.apache.spark.SecurityManager
import org.apache.spark.deploy.Command
import org.apache.spark.launcher.WorkerCommandBuilder
import org.apache.spark.util.Utils

/**
 ** Utilities for running commands with the spark classpath.
 */
private[deploy]
object CommandUtils extends Logging {

  /**
    * 基于给定的参数创建ProcessBuilder
   * Build a ProcessBuilder based on the given parameters.
   * The `env` argument is exposed for testing.
   */
  def buildProcessBuilder(
      command: Command,
      securityMgr: SecurityManager,
      memory: Int,
      sparkHome: String,
      substituteArguments: String => String,
      classPaths: Seq[String] = Seq[String](),
      env: Map[String, String] = sys.env): ProcessBuilder = {
    //ProcessBuilder实例管理一个进程属性集
    val localCommand = buildLocalCommand(
      command, securityMgr, substituteArguments, classPaths, env)
    val commandSeq = buildCommandSeq(localCommand, memory, sparkHome)
    //ProcessBuilder此类用于创建操作系统进程，它提供一种启动和管理进程（也就是应用程序）的方法
    val builder = new ProcessBuilder(commandSeq: _*)
    val environment = builder.environment()
    for ((key, value) <- localCommand.environment) {
      environment.put(key, value)
    }
    builder
  }

  /**
    * 用于构建命令行参数序列
    * @param command
    * @param memory
    * @param sparkHome
    * @return
    */
  private def buildCommandSeq(command: Command, memory: Int, sparkHome: String): Seq[String] = {
    // SPARK-698: do not call the run.cmd script, as process.destroy()
    // fails to kill a process tree on Windows
    val cmd = new WorkerCommandBuilder(sparkHome, memory, command).buildCommand()
    cmd.toSeq ++ Seq(command.mainClass) ++ command.arguments
  }

  /**
    *通过复制ApplicationDescription中的类路径,包路径,环境变量,Java选项参数等信息,在本地创建Command
   * Build a command based on the given one, taking into account the local environment
   * of where this command is expected to run, substitute any placeholders, and append
   * any extra class paths.
   */
  private def buildLocalCommand(
      command: Command,
      securityMgr: SecurityManager,
      substituteArguments: String => String,
      classPath: Seq[String] = Seq[String](),
      env: Map[String, String]): Command = {
    val libraryPathName = Utils.libraryPathEnvName
    val libraryPathEntries = command.libraryPathEntries
    val cmdLibraryPath = command.environment.get(libraryPathName)

    var newEnvironment = if (libraryPathEntries.nonEmpty && libraryPathName.nonEmpty) {
      val libraryPaths = libraryPathEntries ++ cmdLibraryPath ++ env.get(libraryPathName)
      command.environment + ((libraryPathName, libraryPaths.mkString(File.pathSeparator)))
    } else {
      command.environment
    }

    // set auth secret to env variable if needed
    if (securityMgr.isAuthenticationEnabled) {
      newEnvironment += (SecurityManager.ENV_AUTH_SECRET -> securityMgr.getSecretKey)
    }

    Command(
      command.mainClass,
      command.arguments.map(substituteArguments),
      newEnvironment,
      command.classPathEntries ++ classPath,
      Seq[String](), // library path already captured in environment variable
      // filter out auth secret from java options
      command.javaOpts.filterNot(_.startsWith("-D" + SecurityManager.SPARK_AUTH_SECRET_CONF)))
  }

  /** Spawn a thread that will redirect a given stream to a file */
  def redirectStream(in: InputStream, file: File) {
    val out = new FileOutputStream(file, true)
    // TODO: It would be nice to add a shutdown hook here that explains why the output is
    //       terminating. Otherwise if the worker dies the executor logs will silently stop.
    new Thread("redirect output to " + file) {
      override def run() {
        try {
          Utils.copyStream(in, out, true)
        } catch {
          case e: IOException =>
            logInfo("Redirection to " + file + " closed: " + e.getMessage)
        }
      }
    }.start()
  }
}
