/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.List;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import java.util.HashSet;
import core.Connection;
import java.util.Collection;
import java.util.Map;
import java.util.HashMap;
import static routing.MessageRouter.RCV_OK;
import routing.util.EnergyModel;
import util.Tuple;

/**
 * Implementation of Spray and wait router as depicted in 
 * <I>Spray and Wait: An Efficient Routing Scheme for Intermittently
 * Connected Mobile Networks</I> by Thrasyvoulos Spyropoulus et al.
 *
 */
public class E_SprayAndWaitRouter extends ActiveRouter {
	private static double threshold;
	private static double battery_level_threshold; // Minimum battery power
	private static int transFactor; //transmission factor
	public static final String SprayAndWait_NS = "SprayAndWaitRouter";
	public Map<String, Integer> delivered;//sbu msg ID, source ID and destination ID
	
	
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value})*/ 
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String SPRAYANDWAIT_NS = "E_SprayAndWaitRouter";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SPRAYANDWAIT_NS + "." +
		"copies";
	
	protected int initialNrofCopies;
	protected boolean isBinary;
	static {
		Settings s = new Settings();
		threshold = s.getDouble("SprayAndWaitRouter.threshold");
		battery_level_threshold = s.getDouble("SprayAndWaitRouter.battery_level_threshold");
		transFactor = s.getInt("SprayAndWaitRouter.transmissionFactor");
	}

	public E_SprayAndWaitRouter(Settings s) {
		super(s);
		Settings snwSettings = new Settings(SPRAYANDWAIT_NS);
		
		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean( BINARY_MODE);
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected E_SprayAndWaitRouter(E_SprayAndWaitRouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
		this.delivered = new HashMap<String, Integer>(200);
	}
	
	@Override
	public int receiveMessage(Message msg, DTNHost from) {
		
		if (msg.getSize() == -1) {///-1 represent ack_M if the msg size is -1, there if it ack_M, do the following
			String ack_m = msg.getId();//we create the object to contan msg ID
			this.delivered.put(ack_m, 1);// we put the msd ID to ack_table
			String[] parts = ack_m.split("<->");//
			String m_Id = parts[0];
			this.deleteMessage(m_Id, false);// Delete with that ID
			return 0;
		    //return super.receiveMessage(msg, from);
	}
		
		int i = super.receiveMessage(msg, from);
		if (msg.getTo().equals(this.getHost()) && i != RCV_OK) {
		String ack_m = msg.getId() + "<->" + msg.getFrom().toString() + "<->" + msg.getTo().toString();//delear new ack_m to be sent to the last sender node
		Message ack_mes = new Message(this.getHost(), from, ack_m, -1);//creating ack_m with the size -1
		from.receiveMessage(ack_mes, this.getHost());//get the host for the last sender
		this.delivered.put(ack_m, 1);// send the ack_m to the last sender
		}
		return i;
		}
	
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
		Integer nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		
		assert nrofCopies != null : "Not a SnW message: " + msg;
		
		if (isBinary) {
			/* in binary S'n'W the receiving node gets ceil(n/2) copies */
			nrofCopies = (int)Math.ceil(nrofCopies/2.0);
		}
		else {
			/* in standard S'n'W the receiving node gets only single copy */
			nrofCopies = 1;
		}
		
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
		return msg;
	}
	
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());

		msg.setTtl(this.msgTtl);
		msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
		addToMessages(msg, true);
		return true;
	}
	
	@Override
	public void update() {
		super.update();
		if (!canStartTransfer() || isTransferring()) {
			return; // nothing to transfer or is currently transferring 
		}

		/* try messages that could be delivered to final recipient */
		if (exchangeDeliverableMessages() != null) {
			return;
		}
		
		/* create a list of SAWMessages that have copies left to distribute */
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());
		
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			this.tryMessagesToConnections(copiesLeft, getConnections());
		}
	}
	
	/**
	 * Creates and returns a list of messages this router is currently
	 * carrying and still has copies left to distribute (nrof copies > 1).
	 * @return A list of messages that have copies left
	 */
	protected List<Message> getMessagesWithCopiesLeft() {
		List<Message> list = new ArrayList<Message>();
		Collection<Message> msgCollection = getMessageCollection();
		Collection<Message> msg_to_be_deleted = new HashSet<Message>();
		for (Connection con : getConnections()) {
		DTNHost other = con.getOtherNode(getHost());
		E_SprayAndWaitRouter othRouter = (E_SprayAndWaitRouter) other.getRouter();
		if (othRouter.isTransferring()) {
		continue;
		}
		for (Message m : msgCollection) {
		if (othRouter.hasMessage(m.getId())) {
		continue;
		}
		double curr_energy = (double) othRouter.getHost().getComBus().getProperty(EnergyModel.ENERGY_VALUE_ID);
		DTNHost dest = m.getTo();
		String key = m.getId() + "<->" + m.getFrom().toString() + "<->" + dest.toString(); //we are getting the msg ID, Source ID and the destinatio ID od the current msg (Ack_table)
		if (othRouter.delivered.containsKey(key)) {
		int cnt = (int) othRouter.delivered.get(key);
		this.delivered.put(key, ++cnt);///update Ack_Table
		msg_to_be_deleted.add(m);//delate the msg
		continue;
		}
		
		// if energy of neighbour host is less than the battery level threshold and neighbour host is not the destination node
		if (curr_energy < this.battery_level_threshold && !dest.equals(other)) {
		continue;
		}
		//if other node is destination node then Increase computional performance and save energy by skipping metric computions
		if (dest.equals(other)) {
		list.add(m);
		this.delivered.put(key, 1);
		}
		Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
		assert nrofCopies != null : "SnW message " + m + " didn't have "
		+ "nrof copies property!";
		if (nrofCopies > 1) {
		list.add(m);
		}
		}
		if (msg_to_be_deleted.size() > 0) {
		for (Message m : msg_to_be_deleted) {
		this.deleteMessage(m.getId(), false);
		}
		return list;
		}
		}
		return list;
		}
	
	/**
	 * Called just before a transfer is finalized (by 
	 * {@link ActiveRouter#update()}).
	 * Reduces the number of copies we have left for a message. 
	 * In binary Spray and Wait, sending host is left with floor(n/2) copies,
	 * but in standard mode, nrof copies left is reduced by one. 
	 */
	@Override
	protected void transferDone(Connection con) {
		Integer nrofCopies;
		String msgId = con.getMessage().getId();
		/* get this router's copy of the message */
		Message msg = getMessage(msgId);

		if (msg == null) { // message has been dropped from the buffer after..
			return; // ..start of transfer -> no need to reduce amount of copies
		}
		
		/* reduce the amount of copies left */
		nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		if (isBinary) { 
			nrofCopies /= 2;
		}
		else {
			nrofCopies--;
		}
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
	}
	
	@Override
	public E_SprayAndWaitRouter replicate() {
		return new E_SprayAndWaitRouter(this);
	}
}
