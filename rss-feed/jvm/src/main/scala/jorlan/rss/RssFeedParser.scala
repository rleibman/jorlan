/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.rss

import org.xml.sax.InputSource
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Element, Node, NodeList}
import scala.annotation.tailrec

/** Pure XML parser supporting RSS 2.0 and Atom 1.0 feeds. */
object RssFeedParser {

  def parse(
    xml:     String,
    feedUrl: String,
    limit:   Int = 20,
  ): Either[String, List[RssEntry]] =
    scala.util.Try {
      val factory = DocumentBuilderFactory.newInstance()
      factory.setNamespaceAware(true)
      val builder = factory.newDocumentBuilder()
      val source = new InputSource(new java.io.StringReader(xml))
      val doc = builder.parse(source)
      val root = doc.getDocumentElement
      root.getTagName match {
        case tag if tag == "rss" || tag.endsWith(":rss") => parseRss(root, feedUrl, limit)
        case tag if tag == "feed" || tag.endsWith(":feed") => parseAtom(root, feedUrl, limit)
        case other => throw new IllegalArgumentException(s"Unrecognised feed root element: '$other'")
      }
    }.toEither.left.map(_.getMessage)

  private def parseRss(
    root:    Element,
    feedUrl: String,
    limit:   Int,
  ): List[RssEntry] = {
    val items = root.getElementsByTagName("item")
    nodeListToSeq(items).take(limit).map { node =>
      val el = node.asInstanceOf[Element]
      RssEntry(
        title = childText(el, "title"),
        link = childText(el, "link"),
        description = childText(el, "description"),
        pubDate = childText(el, "pubDate"),
        feedUrl = feedUrl,
      )
    }
  }

  private def parseAtom(
    root:    Element,
    feedUrl: String,
    limit:   Int,
  ): List[RssEntry] = {
    val entries = root.getElementsByTagName("entry")
    nodeListToSeq(entries).take(limit).map { node =>
      val el = node.asInstanceOf[Element]
      val link = {
        val linkEls = el.getElementsByTagName("link")
        nodeListToSeq(linkEls)
          .collectFirst { case e: Element =>
            val rel = e.getAttribute("rel")
            if (rel == "alternate" || rel.isEmpty) e.getAttribute("href") else ""
          }.filter(_.nonEmpty)
          .getOrElse("")
      }
      val description = {
        val summary = childText(el, "summary")
        if (summary.nonEmpty) summary else childText(el, "content")
      }
      val pubDate = {
        val published = childText(el, "published")
        if (published.nonEmpty) published else childText(el, "updated")
      }
      RssEntry(
        title = childText(el, "title"),
        link = link,
        description = description,
        pubDate = pubDate,
        feedUrl = feedUrl,
      )
    }
  }

  private def childText(
    parent: Element,
    tag:    String,
  ): String = {
    val nl = parent.getElementsByTagName(tag)
    if (nl.getLength == 0) ""
    else {
      val el = nl.item(0)
      if (el == null) "" else el.getTextContent.nn.trim
    }
  }

  private def nodeListToSeq(nl: NodeList): List[Node] = {
    val buf = scala.collection.mutable.ListBuffer.empty[Node]
    var i = 0
    while (i < nl.getLength) {
      buf += nl.item(i)
      i += 1
    }
    buf.toList
  }

}
