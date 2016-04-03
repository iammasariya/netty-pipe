/**
 * Copyright 2016 Gash.
 *
 * This file and intellectual content is protected under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package gash.router.server.edges;

import gash.router.server.CommandInit;
import gash.router.server.WorkInit;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gash.router.container.RoutingConf.RoutingEntry;
import gash.router.server.ServerState;
import pipe.common.Common.Header;
import pipe.work.Work.Heartbeat;
import pipe.work.Work.WorkMessage;
import pipe.work.Work.WorkState;

import gash.router.client.CommConnection;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

public class EdgeMonitor implements EdgeListener, Runnable {
	protected static Logger logger = LoggerFactory.getLogger("edge monitor");
	protected static AtomicReference<EdgeMonitor> instance = new AtomicReference<EdgeMonitor>(); // Pranav 4/2/2016

	private static EdgeList outboundEdges;
	private static EdgeList inboundEdges;
	private long dt = 2000;
	private ServerState state;
	private boolean forever = true;

	//pranav
	private EventLoopGroup group;
	private ChannelFuture channelFuture;

	public EdgeMonitor(ServerState state) {
		if (state == null)
			throw new RuntimeException("state is null");

		this.outboundEdges = new EdgeList();
		this.inboundEdges = new EdgeList();
		this.state = state;
		this.state.setEmon(this);

		if (state.getConf().getRouting() != null) {
			for (RoutingEntry e : state.getConf().getRouting()) {
				outboundEdges.addNode(e.getId(), e.getHost(), e.getPort());
			}
		}
		instance.compareAndSet(null,this); //4/2/2016

		// cannot go below 2 sec
		if (state.getConf().getHeartbeatDt() > this.dt)
			this.dt = state.getConf().getHeartbeatDt();
	}

	/*BOC Pranav
		4/0/2016
		EdgeMonitor instance
	 */
	public static EdgeMonitor getInstance(){
		return instance.get();
	}
	//EOC

	public void createInboundIfNew(int ref, String host, int port) {
		inboundEdges.createIfNew(ref, host, port);
	}

	private WorkMessage createHB(EdgeInfo ei) {
		WorkState.Builder sb = WorkState.newBuilder();
		sb.setEnqueued(-1);
		sb.setProcessed(-1);

		Heartbeat.Builder bb = Heartbeat.newBuilder();
		bb.setState(sb);

		Header.Builder hb = Header.newBuilder();
		hb.setNodeId(state.getConf().getNodeId());
		hb.setDestination(-1);
		hb.setTime(System.currentTimeMillis());

		WorkMessage.Builder wb = WorkMessage.newBuilder();
		wb.setHeader(hb);
		wb.setBeat(bb);
		wb.setSecret(12345678);//added by manthan
		return wb.build();
	}

	public void shutdown() {
		forever = false;
	}

	@Override
	public void run() {
		while (forever) {
			try {
				for (EdgeInfo ei : this.outboundEdges.map.values()) {
					if (ei.isActive() && ei.getChannel() != null) {
						WorkMessage wm = createHB(ei);
						logger.info("HeartBeat to: " + ei.getRef()); // Pranav
						ei.getChannel().writeAndFlush(wm);
					} else {
						// TODO create a client to the node
						Channel channel = channelInit(ei.getHost(),ei.getPort());
						logger.info("trying to connect to node " + ei.getRef());
						//CommConnection commC = CommConnection.initConnection(ei.getHost(),ei.getPort());
						ei.setChannel(channel); //pranav
						ei.setActive(true);
						logger.info("connected to node channel " + ei.getRef() + ei.isActive()+ei.getChannel());
					}
				}

				Thread.sleep(dt);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}


	//Create Channels for edge heartbeat
	public Channel channelInit(String host, int port)
	{
		try
		{
			group = new NioEventLoopGroup();
			WorkInit wi = new WorkInit(state, false);
			Bootstrap b = new Bootstrap();
			b.group(group).channel(NioSocketChannel.class).handler(wi);
			b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
			b.option(ChannelOption.TCP_NODELAY, true);
			b.option(ChannelOption.SO_KEEPALIVE, true);

			// Make the connection attempt.
			channelFuture = b.connect(host, port).syncUninterruptibly();
		}
		catch(Throwable ex)
		{
			logger.error("Error initializing channel: " + ex);
		}
		return channelFuture.channel();
	}

	@Override
	public synchronized void onAdd(EdgeInfo ei) {
		// TODO check connection //added by Manthan
		if(!ei.isActive() || ei.getChannel() == null){
			logger.info("New edge added, trying to connect to node " + ei.getRef());
			CommConnection commC = CommConnection.initConnection(ei.getHost(),ei.getPort());
			ei.setChannel(commC.getChannel());
			ei.setActive(true);
			logger.info("New edge added and connected to node " + ei.getRef() + ei.isActive());
		}
	}

	@Override
	public synchronized void onRemove(EdgeInfo ei) {
		// TODO ? //added by Manthan
		if(ei.isActive() || ei.getChannel() != null){
			logger.info("Edge removed, trying to disconnect to node " + ei.getRef());
			ei.getChannel().close();
			ei.setActive(false);
			outboundEdges.removeNode(ei.getRef());
			logger.info("Edge removed and disconnected from node " + ei.getRef() + ei.isActive());
			ei = null; // making it available for garbage collection
		}
	}

	public Collection<EdgeInfo> getOutboundEdgeInfoList(){
		return outboundEdges.map.values();
	}

	/**
	 * Author : Manthan
	 * */
	public void updateState(ServerState newState){
		EdgeInfo newOutboundEdge = null;
		this.state = newState;
		this.state.setEmon(this);

		if (state.getConf().getRouting() != null) {
			for (RoutingEntry e : state.getConf().getRouting()) {
				newOutboundEdge = outboundEdges.createIfNew(e.getId(), e.getHost(), e.getPort());
				if(newOutboundEdge!= null)
					onAdd(newOutboundEdge);
			}
		}

		// cannot go below 2 sec
		if (state.getConf().getHeartbeatDt() > this.dt)
			this.dt = state.getConf().getHeartbeatDt();

		newOutboundEdge = null;
	}

	public static Channel getConnection(Integer host)
	{
		return outboundEdges.getNode(host).getChannel();
	}
}
