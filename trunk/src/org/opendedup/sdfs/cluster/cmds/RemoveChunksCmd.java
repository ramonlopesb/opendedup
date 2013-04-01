package org.opendedup.sdfs.cluster.cmds;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.jgroups.Message;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.ResponseMode;
import org.jgroups.util.Rsp;
import org.jgroups.util.RspList;
import org.jgroups.util.Util;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.cluster.DSEClientSocket;
import org.opendedup.sdfs.notification.SDFSEvent;

public class RemoveChunksCmd implements IOClientCmd {
	boolean exists = false;
	RequestOptions opts = null;
	boolean force = false;
	long time = 0;
	SDFSEvent evt = null;
	public long processed;
	

	public RemoveChunksCmd(long time,boolean force,SDFSEvent evt) {
		opts = new RequestOptions(ResponseMode.GET_FIRST,
				0);
		this.time = time;
		this.force = force;
		this.evt = evt;
		
	}

	@Override
	public void executeCmd(final DSEClientSocket soc) throws IOException {
		byte [] ob = null;
		try {
			ob = Util.objectToByteBuffer(evt);
		} catch (Exception e1) {
			throw new IOException(e1);
		}
		byte[] b = new byte[1 + 8 + 1 + 4 + ob.length];
		ByteBuffer buf = ByteBuffer.wrap(b);
		buf.put(NetworkCMDS.RUN_REMOVE);
		buf.putLong(time);
		if(force)
			buf.put((byte)1);
		else
			buf.put((byte)0);
		buf.putInt(ob.length);
		buf.put(ob);
		try {
			RspList<Object> lst = soc.disp.castMessage(null, new Message(null,
					null, buf.array()), opts);
			for(Rsp<Object> rsp : lst) {
				if(rsp.hasException()) {
					SDFSLogger.getLog().error("FDISK Exception thrown for " + rsp.getSender());
					throw rsp.getException();
				} else if(rsp.wasSuspected() | rsp.wasUnreachable()) {
					SDFSLogger.getLog().error("FDISK Host unreachable Exception thrown ");
					throw new IOException("FDISK Host unreachable Exception thrown ");
				}
				else {
					if(rsp.getValue() != null) {
						SDFSLogger.getLog().debug("Claim completed for " +rsp.getSender() + " returned=" +rsp.getValue());
						SDFSEvent evt = (SDFSEvent)rsp.getValue();
						ArrayList<SDFSEvent> children = evt.getChildren();
						for(SDFSEvent cevt : children) {
							evt.addChild(cevt);
							if(evt.type == SDFSEvent.REMOVER)
								this.processed = evt.actionCount;
						}
						
					}
				}
			}
		} catch (Throwable e) {
			SDFSLogger.getLog().error("error while running fdisk", e);
			throw new IOException(e);
		}
	}
	

	@Override
	public byte getCmdID() {
		return NetworkCMDS.RUN_REMOVE;
	}
	
	public long removedHashesCount() {
		return this.processed;
	}

}
