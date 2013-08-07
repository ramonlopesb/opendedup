package org.opendedup.sdfs.cluster;

import java.util.List;
import java.util.concurrent.locks.Lock;

import org.jgroups.Address;
import org.jgroups.blocks.MessageDispatcher;
import org.opendedup.sdfs.cluster.cmds.DSEServer;

public interface ClusterSocket {
	public abstract List<DSEServer> getStorageNodes();
	public abstract List<DSEServer> getNameNodes();
	public abstract List<String> getVolumes();
	public abstract Address getAddressForVol(String volumeName);
	public abstract Lock getLock(String name);
	public abstract boolean isPeerMaster();
	public abstract MessageDispatcher getDispatcher();
}
