package org.opendedup.sdfs.replication;

import java.io.File;


import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;


import org.opendedup.logging.SDFSLogger;
import org.opendedup.mtools.ClusterRedundancyCheck;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.filestore.MetaFileStore;
import org.opendedup.sdfs.filestore.gc.GCMain;
import org.opendedup.sdfs.io.MetaDataDedupFile;
import org.opendedup.sdfs.notification.SDFSEvent;
import org.opendedup.util.FileCounts;
import org.opendedup.util.RandomGUID;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import de.schlichtherle.truezip.file.TArchiveDetector;
import de.schlichtherle.truezip.file.TFile;
import de.schlichtherle.truezip.file.TVFS;


public class ArchiveImporter {

	private boolean closed = false;
	private static ConcurrentHashMap<String, ArchiveImporter> runningJobs = new ConcurrentHashMap<String, ArchiveImporter>();
	SDFSEvent ievt = null;
	MetaFileImport imp = null;

	public static void stopJob(String id) {
		runningJobs.get(id).close();
	}

	public void close() {
		this.closed = true;
		if (this.imp != null)
			imp.close();
	}

	public Element importArchive(String srcArchive, String dest, String server,
			String password, int port, int maxSz, SDFSEvent evt, boolean useSSL)
			throws Exception {
		ievt = SDFSEvent.archiveImportEvent("Importing " + srcArchive
				+ " from " + server + ":" + port + " to " + dest, evt);
		ReadLock l = GCMain.gclock.readLock();
		l.lock();
		TFile fDstFiles = null;
		try {
			runningJobs.put(evt.uid, this);
			File f = new File(srcArchive);
			String sdest = dest + "." + RandomGUID.getGuid();
			SDFSLogger.getLog().info("setting up staging at " + sdest);
			try {
				SDFSLogger.getLog().info(
						"Importing " + srcArchive + " from " + server + ":"
								+ port + " to " + dest);
				if (!f.exists())
					throw new IOException("File does not exist " + srcArchive);
				TFile srcRoot = new TFile(new File(srcArchive + "/"));
				ievt.maxCt = FileCounts.getSize(srcRoot);
				SDFSLogger.getLog().info("Tar file size is " + ievt.maxCt);
				TFile srcFilesRoot = new TFile(new File(srcArchive + "/files/"));
				TFile srcFiles = null;
				try {
					srcFiles = srcFilesRoot.listFiles()[0];
				} catch (Exception e) {
					SDFSLogger.getLog().error(
							"Replication archive is corrupt " + srcArchive + " size of " + new File(srcArchive).length(), e);
					throw e;
				}
				fDstFiles = new TFile(Main.volume.getPath() + File.separator
						+ sdest);
				this.export(srcFiles, fDstFiles);
				srcFiles = new TFile(new File(srcArchive + "/ddb/"));
				File ddb = new File(Main.dedupDBStore + File.separator);
				if (!ddb.exists())
					ddb.mkdirs();
				TFile mDstFiles  = new TFile(Main.dedupDBStore + File.separator);
				this.export(srcFiles, mDstFiles);
				TVFS.umount(srcRoot.getInnerArchive());
				
				imp = new MetaFileImport(Main.volume.getPath() + File.separator
						+ sdest, server, password, port, maxSz, evt, useSSL);

				imp.runImport();
				if (imp.isCorrupt()) {
					// evt.endEvent("Import failed for " + srcArchive +
					// " because not all the data could be imported from " +
					// server,SDFSEvent.WARN);
					SDFSLogger
							.getLog()
							.warn("Import failed for "
									+ srcArchive
									+ " because not all the data could be imported from "
									+ server);
					SDFSLogger.getLog().warn("rolling back import");
					rollBackImport(Main.volume.getPath() + File.separator
							+ sdest);
					SDFSLogger.getLog().warn("Import rolled back");
					throw new IOException(
							"uable to import files: There are files that are missing blocks");
				} else {
					if (!Main.chunkStoreLocal)
						new ClusterRedundancyCheck(ievt, new File(
								Main.volume.getPath() + File.separator + sdest),true);
					commitImport(Main.volume.getPath() + File.separator + dest,
							Main.volume.getPath() + File.separator + sdest);
					DocumentBuilderFactory factory = DocumentBuilderFactory
							.newInstance();
					DocumentBuilder builder;
					builder = factory.newDocumentBuilder();

					DOMImplementation impl = builder.getDOMImplementation();
					// Document.
					Document doc = impl.createDocument(null,
							"replication-import", null);
					// Root element.
					Element root = doc.getDocumentElement();
					root.setAttribute("src", srcArchive);
					root.setAttribute("dest", dest);
					root.setAttribute("srcserver", server);
					root.setAttribute("srcserverport", Integer.toString(port));
					root.setAttribute("batchsize", Integer.toString(maxSz));
					root.setAttribute("filesimported",
							Long.toString(imp.getFilesProcessed()));
					root.setAttribute("bytesimported",
							Long.toString(imp.getBytesTransmitted()));
					root.setAttribute("entriesimported",
							Long.toString(imp.getEntries()));
					root.setAttribute("virtualbytesimported",
							Long.toString(imp.getVirtualBytesTransmitted()));
					root.setAttribute("starttime",
							Long.toString(imp.getStartTime()));
					root.setAttribute("endtime",
							Long.toString(imp.getEndTime()));
					root.setAttribute("volume", Main.volume.getName());
					root.setAttribute("volumeconfig",
							Main.volume.getConfigPath());
					evt.endEvent(srcArchive + " from " + server + ":" + port
							+ " to " + dest + " imported successfully");
					return (Element) root.cloneNode(true);
				}
			} catch (Exception e) {
				SDFSLogger.getLog().warn("rolling back import ", e);
				rollBackImport(Main.volume.getPath() + File.separator + sdest);
				SDFSLogger.getLog().warn("Import rolled back");

				if (!evt.isDone())
					evt.endEvent("Import failed and was rolled back ",
							SDFSEvent.ERROR, e);
				throw e;
			}
		} finally {
			try {
				fDstFiles.rm();
			} catch(Exception e) {
				SDFSLogger.getLog().debug(e);
			}
			runningJobs.remove(evt.uid);
			l.unlock();
		}

	}

	public void rollBackImport(String path) {
		try {
			path = new File(path).getPath();
			MetaDataDedupFile mf = MetaFileStore.getMF(path);

			if (mf.isDirectory()) {
				String[] files = mf.list();
				for (int i = 0; i < files.length; i++) {
					MetaDataDedupFile _mf = MetaFileStore.getMF(files[i]);
					if (_mf.isDirectory())
						rollBackImport(_mf.getPath());
					else {
						MetaFileStore.removeMetaFile(_mf.getPath(), true);
					}
				}
			}
			MetaFileStore.removeMetaFile(mf.getPath(), true);
		} catch (Exception e) {
			SDFSLogger.getLog().warn(
					"unable to remove " + path + " during rollback ", e);
		}
	}

	public void commitImport(String dest, String sdest) throws IOException {
		File f = new File(dest);
		if (f.exists()) {
			try {
				MetaDataDedupFile mf = MetaFileStore.getMF(dest);
				MetaFileStore.removeMetaFile(mf.getPath(), true);
			} catch (Exception e) {
				SDFSLogger.getLog().error(
						"unable to commit replication while removing old data in ["
								+ dest + "]", e);
				throw new IOException(
						"unable to commit replication while removing old data in ["
								+ dest + "]");
			}
		}
		try {
			MetaDataDedupFile nmf = MetaFileStore.getMF(sdest);
			nmf.renameTo(dest, true);
			MetaFileStore.removeMetaFile(sdest,true);
			SDFSLogger.getLog().info("moved " + sdest + " to " + dest);
			
		} catch (Exception e) {
			SDFSLogger.getLog().error(
					"unable to commit replication while moving from staing ["
							+ sdest + "] to [" + dest + "]", e);
			throw new IOException(
					"unable to commit replication while moving from staing ["
							+ sdest + "] to [" + dest + "]");

		}
	}

	private void export(TFile file, TFile dst)
			throws ReplicationCanceledException, IOException {
		SDFSLogger.getLog().debug("extracting " +file.getPath() + " to " + dst.getPath());
		if (!closed) {
			TFile.cp_rp(file, dst, TArchiveDetector.NULL);
			/*
			if (file.isDirectory()) {
				dst.mkdirs();
				// All files and subdirectories
				TFile[] files = file.listFiles();
				for (int i = 0; i < files.length; i++) {
					File dstF = new File(dst, files[i].getName());
					if (files[i].isFile()) {
						files[i].cp_p(dstF);
						ievt.curCt += dstF.length();
					} else {
						export(files[i], dstF);
					}
				}
			} else {
				dst.getParentFile().mkdirs();

			}
			*/
			

		} else {
			throw new ReplicationCanceledException(
					"replication job was canceled");
		}
	}
	
	public static void main(String [] args)  {
		String srcArchive= "/tmp/test.zip";
		TFile srcRoot = new TFile(new File(srcArchive + "/test/"));
		System.out.println("Tar file size is " + FileCounts.getSize(srcRoot));
		TFile [] srcFiles = srcRoot.listFiles();
		for(TFile f: srcFiles) {
			System.out.println("file=" + f.getName());
		}
		
		
	}

}
