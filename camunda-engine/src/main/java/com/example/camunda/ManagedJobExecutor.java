package com.example.camunda;

import org.camunda.bpm.engine.impl.ProcessEngineImpl;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;

/**
 * Hands job execution off to a container-managed ExecutorService (JBoss
 * EAP's default ManagedExecutorService) instead of the ThreadPoolExecutor
 * that JobExecutor's default subclass (ThreadPoolJobExecutor) would create
 * and manage itself. Pure JEE (javax.naming, java.util.concurrent) - no
 * Spring types, so this also works standing alone if wired up from plain
 * Java code instead of camunda.cfg.xml.
 *
 * Looks the executor up itself (rather than taking it as a constructor
 * argument) so it can be built from camunda.cfg.xml as a plain
 * <bean class="..." init-method="init"> with just a JNDI name string
 * property - no Java object needs to be resolved and injected by whatever
 * is parsing that XML.
 *
 * The job-acquisition polling thread (started/stopped below) remains a
 * single dedicated background Thread managed by the engine itself - the
 * same is true even under Camunda's official WildFly subsystem, which only
 * containerizes the *execution* thread pool, not the acquisition loop.
 */
public class ManagedJobExecutor extends JobExecutor {

    private static final Logger LOG = Logger.getLogger(ManagedJobExecutor.class.getName());

    private String executorServiceJndiName = "java:jboss/ee/concurrency/executor/default";
    private ExecutorService executorService;

    public void setExecutorServiceJndiName(String executorServiceJndiName) {
        this.executorServiceJndiName = executorServiceJndiName;
    }

    /**
     * Called via camunda.cfg.xml's init-method="init" after the bean's
     * properties have been set.
     */
    public void init() throws NamingException {
        this.executorService = (ExecutorService) new InitialContext().lookup(executorServiceJndiName);
    }

    @Override
    protected void startExecutingJobs() {
        startJobAcquisitionThread();
    }

    @Override
    protected void stopExecutingJobs() {
        stopJobAcquisitionThread();
    }

    @Override
    public void executeJobs(List<String> jobIds, ProcessEngineImpl processEngine) {
        Runnable executeJobsRunnable = getExecuteJobsRunnable(jobIds, processEngine);
        try {
            executorService.execute(() -> {
                LOG.info(() -> "Executing job(s) " + jobIds + " on thread ["
                        + Thread.currentThread().getName() + "]");
                executeJobsRunnable.run();
            });
        } catch (RejectedExecutionException e) {
            rejectedJobsHandler.jobsRejected(jobIds, processEngine, this);
        }
    }
}
