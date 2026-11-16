package org.codelabuk.perf.sql

import java.util.concurrent.{ConcurrentHashMap, ExecutorService, Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.StrictLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

/** One connection pool + one thread pool of the same size per jdbc connection, per JVM. */
final class Pool(val conn: JdbcConnection, val dataSource: HikariDataSource, val executor: ExecutorService)

object Pools extends StrictLogging {
  private val pools = new ConcurrentHashMap[String, Pool]()

  Runtime.getRuntime.addShutdownHook(new Thread(() => closeAll(), "sql-pools-shutdown"))

  /** Opens the pool on first use - this fails fast (before any load) if the database is unreachable. */
  def get(conn: JdbcConnection): Pool = pools.computeIfAbsent(conn.name, _ => open(conn))

  private def open(c: JdbcConnection): Pool = {
    val cfg = new HikariConfig()
    cfg.setPoolName(s"perf-${c.name}")
    cfg.setJdbcUrl(c.url)
    c.user.foreach(cfg.setUsername)
    c.password.foreach(cfg.setPassword)
    c.driver.foreach(cfg.setDriverClassName)
    cfg.setMaximumPoolSize(c.poolSize)
    cfg.setMinimumIdle(c.poolSize) // open every connection up front, so tests do not measure connection set-up
    cfg.setConnectionTimeout(math.max(250L, c.connectionTimeout.toMillis))
    cfg.setInitializationFailTimeout(1) // unreachable database = immediate, clear error
    c.properties.foreach { case (k, v) => cfg.addDataSourceProperty(k, v) }
    val ds =
      try new HikariDataSource(cfg)
      catch {
        case e: Exception =>
          val cause = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).toList.last
          throw new IllegalStateException(s"Cannot connect to jdbc connection '${c.name}' (${c.url}): ${cause.getMessage}", e)
      }
    val counter = new AtomicInteger
    val threads: ThreadFactory = r => {
      val t = new Thread(r, s"sql-${c.name}-${counter.incrementAndGet()}")
      t.setDaemon(true)
      t
    }
    logger.info(s"jdbc '${c.name}': pool of ${c.poolSize} connection(s) to ${c.url}")
    new Pool(c, ds, Executors.newFixedThreadPool(c.poolSize, threads))
  }

  def closeAll(): Unit = {
    pools.values().forEach { p =>
      p.executor.shutdownNow()
      p.dataSource.close()
    }
    pools.clear()
  }
}
