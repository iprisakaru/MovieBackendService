package by.bsu.model

import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

trait DbConfiguration {
  lazy val config = DatabaseConfig.forConfig[JdbcProfile]("db")
}
