package org.codelabuk.perf.sql

import java.sql.{Connection, DriverManager, ResultSet, Statement}

/** In-memory Derby database for tests: customers, orders, audit + two stored procedures. */
object DerbyFixture {
  val Url = "jdbc:derby:memory:perf"
  @volatile private var ready = false

  def connect(): Connection = DriverManager.getConnection(Url)

  def init(): Unit = synchronized {
    if (!ready) {
      val c = DriverManager.getConnection(Url + ";create=true")
      try {
        val s = c.createStatement()
        s.execute("CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(50), status VARCHAR(10))")
        s.execute("CREATE TABLE orders (id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, customer_id INT, amount DECIMAL(10,2), status VARCHAR(10))")
        s.execute("CREATE TABLE audit (id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, sr VARCHAR(20), note VARCHAR(100))")
        val ins = c.prepareStatement("INSERT INTO customers VALUES (?, ?, ?)")
        (1 to 50).foreach { i =>
          ins.setInt(1, i); ins.setString(2, f"Customer $i%02d"); ins.setString(3, if (i <= 40) "ACTIVE" else "INACTIVE"); ins.executeUpdate()
        }
        // OUT parameter, like an Oracle PL/SQL procedure returning the new id
        s.execute("""CREATE PROCEDURE PLACE_ORDER(IN cust INT, IN amount DECIMAL(10,2), OUT new_id INT)
                    |  LANGUAGE JAVA PARAMETER STYLE JAVA MODIFIES SQL DATA
                    |  EXTERNAL NAME 'org.codelabuk.perf.sql.DerbyProcs.placeOrder'""".stripMargin)
        // returns rows, like a procedure with a REF CURSOR / implicit result
        s.execute("""CREATE PROCEDURE CUSTOMER_ORDERS(IN cust INT)
                    |  LANGUAGE JAVA PARAMETER STYLE JAVA READS SQL DATA DYNAMIC RESULT SETS 1
                    |  EXTERNAL NAME 'org.codelabuk.perf.sql.DerbyProcs.customerOrders'""".stripMargin)
      } finally c.close()
      ready = true
    }
  }
}

/** Stored procedure bodies for Derby (static methods). */
object DerbyProcs {
  def placeOrder(cust: Int, amount: java.math.BigDecimal, newId: Array[Int]): Unit = {
    val c = DriverManager.getConnection("jdbc:default:connection")
    val ps = c.prepareStatement("INSERT INTO orders (customer_id, amount, status) VALUES (?, ?, 'OPEN')", Statement.RETURN_GENERATED_KEYS)
    ps.setInt(1, cust); ps.setBigDecimal(2, amount); ps.executeUpdate()
    val keys = ps.getGeneratedKeys
    keys.next()
    newId(0) = keys.getInt(1)
  }

  def customerOrders(cust: Int, result: Array[ResultSet]): Unit = {
    val c = DriverManager.getConnection("jdbc:default:connection")
    val ps = c.prepareStatement("SELECT id, amount, status FROM orders WHERE customer_id = ? ORDER BY id")
    ps.setInt(1, cust)
    result(0) = ps.executeQuery()
  }
}
