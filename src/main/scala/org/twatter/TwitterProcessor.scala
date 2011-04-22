package org.twatter

import scala.actors._
import Actor._
import java.io.{File, BufferedWriter, FileWriter}
import twitter4j.Status
import redis.clients.jedis.JedisPool
import org.slf4j.{Logger, LoggerFactory}

/**
 * Actor used to process new twitter messages to file and to
 * redis.
 */
class TwitterProcessor(filename:String, database:JedisPool,
    poison: List[String]) extends Actor {

    private val logger = LoggerFactory.getLogger(this.getClass)
    private val output = new File(filename)

    /**
     * The main acting loop for receiving new messages
     */
    def act() {
        loop {
            react {
                case status:Status => {
                    if (shouldSaveStatus(status)) {
                        saveToFile(status)
                        saveToDatabase(status)
                    }
                }
                case "quit" => exit()
            }
        }
    }

    /**
     * Checks if the given messages should be filtered
     *
     * @param status The next status to test
     * @return true if we should save the status, false otherwise
     */
    private def shouldSaveStatus(status:Status) : Boolean = {
        if (poison.isEmpty) return true
        val shouldSave = status.getText.split(" ").map { _.toLowerCase }
            .exists { word => poison.exists { _ contains word } }
        !shouldSave
    }

    /**
     * Saves the next message to the filesystem for processing
     *
     * @param status The next status to save
     */
    private def saveToFile(status:Status) {
        val file   = new File(output, status.getId.toString)
        val writer = new BufferedWriter(new FileWriter(file))
        try {
            writer.write(status.getText)
        } finally {
            writer.close
        }
    }

    /**
     * Saves the next message status to the database

     * @param status The next status to save
     */
    private def saveToDatabase(status:Status) {
        val redis = database.getResource()
        try {
            val id = status.getId.toString
            status.getHashtagEntities.foreach { tag =>
                val text = tag.getText.toLowerCase
                redis.sadd(TwitterRedis.topicsKey,  text)
                redis.sadd(TwitterRedis.topicKey(text), id)
                redis.incr(TwitterRedis.topicCountKey(text))
            }
        } finally {
            database.returnResource(redis)
        }
    }
}
