package org.opendedup.sdfs.filestore.gc;

import java.util.Properties;

import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerFactory;
import org.quartz.impl.StdSchedulerFactory;

public class SDFSGCScheduler {

	Scheduler sched = null;

	public SDFSGCScheduler() {
		try {
			Properties props = new Properties();
			props.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
			props.setProperty("org.quartz.threadPool.class",
					"org.quartz.simpl.SimpleThreadPool");
			props.setProperty("org.quartz.threadPool.threadCount", "1");
			props.setProperty("org.quartz.threadPool.threadPriority",
					Integer.toString(8));
			SDFSLogger.getLog().info("Scheduling FDISK Jobs for SDFS");
			SchedulerFactory schedFact = new StdSchedulerFactory(props);
			sched = schedFact.getScheduler();
			sched.start();
			JobDetail ccjobDetail = new JobDetail("gc", null, GCJob.class);
			CronTrigger cctrigger = new CronTrigger("gcTrigger", "group1",
					Main.fDkiskSchedule);
			sched.scheduleJob(ccjobDetail, cctrigger);
			SDFSLogger.getLog().info("Stand Along Garbage Collection Jobs Scheduled will run first at " + cctrigger.getNextFireTime().toString());
		} catch (Exception e) {
			SDFSLogger.getLog().fatal(
					"Unable to schedule SDFS Garbage Collection", e);
		}
	}

	public void stopSchedules() {
		try {
			sched.unscheduleJob("gc", "gcTrigger");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			// e.printStackTrace();
		}
	}

}