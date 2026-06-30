/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.rss

import org.xml.sax.InputSource
import javax.xml.parsers.{DocumentBuilder, DocumentBuilderFactory}
import org.w3c.dom.{Element, Node, NodeList}

/** Pure XML parser supporting RSS 2.0 and Atom 1.0 feeds. */
object RssFeedParser {

  // Factory is thread-safe and expensive to construct — cache it once.
  lazy private val factory: DocumentBuilderFactory = {
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(true)
    f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    f.setFeature("http://xml.org/sax/features/external-general-entities", false)
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    f.setXIncludeAware(false)
    f.setExpandEntityReferences(false)
    f
  }

  def parse(
    xml:     String,
    feedUrl: String,
    limit:   Int = 20,
  ): Either[String, List[RssEntry]] =
    scala.util
      .Try {
        val builder = factory.newDocumentBuilder()
        val source = new InputSource(new java.io.StringReader(xml))
        val doc = builder.parse(source)
        val root = doc.getDocumentElement
        root.getTagName match {
          case tag if tag == "rss" || tag.endsWith(":rss")   => parseRss(root, feedUrl, limit)
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
    nodeListToSeq(items).take(limit).collect { case el: Element =>
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
    nodeListToSeq(entries).take(limit).collect { case el: Element =>
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

  private def nodeListToSeq(nl: NodeList): List[Node] =
    List.tabulate(nl.getLength)(i => nl.item(i))

}
