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
 * Hands job execution off to a container-managed ExecutorService instead
 * of a self-managed ThreadPoolExecutor. See ADR-2 in
 * docs/arc42/09-architecture-decisions.md and
 * docs/arc42/08-crosscutting-concepts.md §8.1.
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
