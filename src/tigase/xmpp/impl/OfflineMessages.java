/*  Package Jabber Server
 *  Copyright (C) 2001, 2002, 2003, 2004, 2005
 *  "Artur Hefczyc" <kobit@users.sourceforge.net>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software Foundation,
 *  Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.xmpp.impl;

import java.util.logging.Logger;
import java.util.Queue;
import java.util.Date;
import java.util.LinkedList;
import java.text.SimpleDateFormat;
import tigase.util.JID;
import tigase.db.WriteOnlyUserRepository;
import tigase.db.UserNotFoundException;
import tigase.server.Packet;
import tigase.xml.Element;
import tigase.xml.SimpleParser;
import tigase.xml.SingletonFactory;
import tigase.xml.DomBuilderHandler;
import tigase.xmpp.StanzaType;
import tigase.xmpp.XMPPImplIfc;
import tigase.xmpp.XMPPPostprocessorIfc;
import tigase.xmpp.XMPPProcessor;
import tigase.xmpp.XMPPProcessorIfc;
import tigase.xmpp.XMPPResourceConnection;
import tigase.xmpp.OfflineMessageStorage;
import tigase.xmpp.NotAuthorizedException;

/**
 * Describe class OfflineMessages here.
 *
 *
 * Created: Mon Oct 16 13:28:53 2006
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class OfflineMessages extends XMPPProcessor
	implements XMPPPostprocessorIfc, XMPPProcessorIfc {

  /**
   * Private logger for class instancess.
   */
  private static Logger log =
		Logger.getLogger("tigase.xmpp.impl.OfflineMessage");

	protected static final String ID = "msgoffline";
  protected static final String XMLNS = "jabber:client";
	protected static final String[] ELEMENTS = {"presence"};
  protected static final String[] XMLNSS = {XMLNS};
  protected static final String[] DISCO_FEATURES = {"msgoffline"};

	private static final SimpleParser parser =
		SingletonFactory.getParserInstance();
	private static final SimpleDateFormat formater =
		new SimpleDateFormat("yyyyMMdd'T'HH:mm:ss");

	// Implementation of tigase.xmpp.XMPPImplIfc

	/**
	 * Describe <code>supElements</code> method here.
	 *
	 * @return a <code>String[]</code> value
	 */
	public String[] supElements() { return ELEMENTS; }

	/**
	 * Describe <code>supNamespaces</code> method here.
	 *
	 * @return a <code>String[]</code> value
	 */
  public String[] supNamespaces() { return XMLNSS; }

	/**
	 * Describe <code>supDiscoFeatures</code> method here.
	 *
	 * @param XMPPResourceConnection a <code>XMPPResourceConnection</code> value
	 * @return a <code>String[]</code> value
	 */
  public String[] supDiscoFeatures(final XMPPResourceConnection session)
	{ return DISCO_FEATURES; }

	/**
	 * Describe <code>id</code> method here.
	 *
	 * @return a <code>String</code> value
	 */
	public String id() { return ID; }

	// Implementation of tigase.xmpp.XMPPProcessorIfc

	/**
	 * Describe <code>process</code> method here.
	 *
	 * @param packet a <code>Packet</code> value
	 * @param XMPPResourceConnection a <code>XMPPResourceConnection</code> value
	 * @param queue a <code>Queue</code> value
	 */
	public void process(final Packet packet,
		final XMPPResourceConnection conn, final Queue<Packet> results) {

		StanzaType type = packet.getType();
		if (type == null || type == StanzaType.available) {
			// Should we send off-line messages now?
			// Let's try to do it here and maybe later I find better place.
			String priority_str = packet.getElemCData("/presence/priority");
			int priority = 0;
			if (priority_str != null) {
				try {
					priority = Integer.parseInt(priority_str);
				} catch (NumberFormatException e) {
					priority = 0;
				} // end of try-catch
			} // end of if (priority != null)
			if (priority >= 0) {
				try {
					Queue<Packet> packets =	restorePacketForOffLineUser(conn);
					if (packets != null) {
						log.finer("Sending off-line messages: " + packets.size());
						results.addAll(packets);
					} // end of if (packets != null)
				} catch (NotAuthorizedException e) {	} // end of try-catch
			} // end of if (priority >= 0)
		} // end of if (type == null || type == StanzaType.available)

	}

	// Implementation of tigase.xmpp.XMPPPostprocessorIfc

	/**
	 * Describe <code>postProcess</code> method here.
	 *
	 * @param packet a <code>Packet</code> value
	 * @param XMPPResourceConnection a <code>XMPPResourceConnection</code> value
	 * @param writeOnlyUserRepository a <code>WriteOnlyUserRepository</code> value
	 * @param queue a <code>Queue</code> value
	 */
	public void postProcess(final Packet packet,
		final XMPPResourceConnection conn,	final WriteOnlyUserRepository woRep,
		final Queue<Packet> queue) {
		if (!packet.wasProcessed()) {
			try {
				savePacketForOffLineUser(packet, woRep);
				packet.processedBy(ID);
			} catch (UserNotFoundException e) {
				log.finest("UserNotFoundException at trying to save packet for off-line user."
					+ packet.getStringData());
			} // end of try-catch
		} // end of if (!packet.wasProcessed())
	}

	public void savePacketForOffLineUser(Packet pac, WriteOnlyUserRepository woRep)
		throws UserNotFoundException {

		StanzaType type = pac.getType();

		if ((pac.getElemName().equals("message") &&
				(type == null || type == StanzaType.normal || type == StanzaType.chat))
			|| (pac.getElemName().equals("presence") &&
					(type == StanzaType.subscribe || type == StanzaType.subscribed
						|| type == StanzaType.unsubscribe || type == StanzaType.unsubscribed))) {
			Element packet = (Element)pac.getElement().clone();
			String stamp = null;
			synchronized (formater) {
				stamp = formater.format(new Date());
			}
			String from = JID.getNodeHost(pac.getElemTo());
			Element x = new Element("x", "Offline Storage",
				new String[] {"from", "stamp", "xmlns"},
				new String[] {from, stamp, "jabber:x:delay"});
			packet.addChild(x);
			String user_id = JID.getNodeID(pac.getElemTo());
			woRep.addDataList(user_id, "off-line",
				"messages", new String[] {packet.toString()});
		} // end of if (pac.getElemName().equals("message"))
	}

	public Queue<Packet> restorePacketForOffLineUser(XMPPResourceConnection conn)
		throws NotAuthorizedException {
		DomBuilderHandler domHandler = new DomBuilderHandler();
		String[] msgs = conn.getDataList("off-line", "messages");
		if (msgs != null && msgs.length > 0) {
			conn.removeDataGroup("off-line");
			Queue<Packet> pacs = new LinkedList<Packet>();
			for (String msg: msgs) {
				char[] data = msg.toCharArray();
				parser.parse(domHandler, data, 0, data.length);
				Queue<Element> elems = domHandler.getParsedElements();
				Element elem = null;
				while ((elem = elems.poll()) != null) {
					pacs.offer(new Packet(elem));
				} // end of while (elem = elems.poll() != null)
			} // end of for (int i = 0; i < msgs.length; i++)
			return pacs;
		} // end of if (msgs != null)
		else {
			return null;
		} // end of if (msgs != null) else
	}

} // OfflineMessages
