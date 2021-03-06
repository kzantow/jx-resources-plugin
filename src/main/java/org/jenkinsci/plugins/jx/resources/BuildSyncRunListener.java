package org.jenkinsci.plugins.jx.resources;

import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import com.cloudbees.workflow.rest.external.StatusExt;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import jenkins.util.Timer;
import org.apache.commons.httpclient.HttpStatus;
import org.jenkinsci.plugins.jx.resources.kube.ClientHelper;
import org.jenkinsci.plugins.jx.resources.kube.DoneablePipelineActivities;
import org.jenkinsci.plugins.jx.resources.kube.KubernetesNames;
import org.jenkinsci.plugins.jx.resources.kube.PipelineActivity;
import org.jenkinsci.plugins.jx.resources.kube.PipelineActivityList;
import org.jenkinsci.plugins.jx.resources.kube.PipelineActivitySpec;
import org.jenkinsci.plugins.jx.resources.kube.PipelineActivityStep;
import org.jenkinsci.plugins.jx.resources.kube.StageActivityStep;
import org.jenkinsci.plugins.jx.resources.kube.Statuses;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.jenkinsci.plugins.jx.resources.KubernetesUtils.formatTimestamp;
import static org.jenkinsci.plugins.jx.resources.KubernetesUtils.getKubernetesClient;
import static org.jenkinsci.plugins.jx.resources.MarkupUtils.toYaml;

/**
 * Listens to Jenkins Job build {@link Run} and updates the PipelineActivity resource
 */
@Extension
public class BuildSyncRunListener extends RunListener<Run> {
    private static final Logger logger = Logger.getLogger(BuildSyncRunListener.class.getName());

    private long pollPeriodMs = 1000;
    private String namespace;

    private transient Set<Run> runsToPoll = new CopyOnWriteArraySet<>();

    private transient AtomicBoolean timerStarted = new AtomicBoolean(false);

    public BuildSyncRunListener() {
    }

    @DataBoundConstructor
    public BuildSyncRunListener(long pollPeriodMs) {
        this.pollPeriodMs = pollPeriodMs;
    }

    /**
     * Joins all the given strings, ignoring nulls so that they form a URL with / between the paths without a // if the
     * previous path ends with / and the next path starts with / unless a path item is blank
     *
     * @param strings the sequence of strings to join
     * @return the strings concatenated together with / while avoiding a double // between non blank strings.
     */
    public static String joinPaths(String... strings) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < strings.length; i++) {
            sb.append(strings[i]);
            if (i < strings.length - 1) {
                sb.append("/");
            }
        }
        String joined = sb.toString();

        // And normalize it...
        return joined
                .replaceAll("/+", "/")
                .replaceAll("/\\?", "?")
                .replaceAll("/#", "#")
                .replaceAll(":/", "://");
    }

    @Override
    public synchronized void onStarted(Run run, TaskListener listener) {
        if (shouldPollRun(run)) {
            if (runsToPoll.add(run)) {
                logger.info("starting polling build " + run.getUrl());
            }
            checkTimerStarted();
        } else {
            logger.fine("not polling polling build " + run.getUrl() + " as its not a WorkflowJob");
        }
        super.onStarted(run, listener);
    }

    protected void checkTimerStarted() {
        if (timerStarted.compareAndSet(false, true)) {
            Runnable task = new SafeTimerTask() {
                @Override
                protected void doRun() throws Exception {
                    pollLoop();
                }
            };
            Timer.get().scheduleAtFixedRate(task, pollPeriodMs, pollPeriodMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void onCompleted(Run run, @Nonnull TaskListener listener) {
        if (shouldPollRun(run)) {
            runsToPoll.remove(run);
            pollRun(run);
        }
        super.onCompleted(run, listener);
    }

    @Override
    public synchronized void onDeleted(Run run) {
        if (shouldPollRun(run)) {
            runsToPoll.remove(run);
            pollRun(run);
        }
        super.onDeleted(run);
    }

    @Override
    public synchronized void onFinalized(Run run) {
        if (shouldPollRun(run)) {
            runsToPoll.remove(run);
            pollRun(run);
        }
        super.onFinalized(run);
    }

    protected synchronized void pollLoop() {
        for (Run run : runsToPoll) {
            pollRun(run);
        }
    }

    protected synchronized void pollRun(Run run) {
        if (!(run instanceof WorkflowRun)) {
            throw new IllegalStateException("Cannot poll a non-workflow run");
        }

        RunExt wfRunExt = RunExt.create((WorkflowRun) run);

        try {
            upsertBuild(run, wfRunExt);
        } catch (KubernetesClientException e) {
            if (e.getCode() == HttpStatus.SC_UNPROCESSABLE_ENTITY) {
                runsToPoll.remove(run);
                logger.log(WARNING, "Cannot update status: {0}", e.getMessage());
                return;
            }
            throw e;
        }
    }

    private void upsertBuild(Run run, RunExt wfRunExt) {
        if (run == null) {
            return;
        }

        long started = getStartTime(run);
        String startTime = null;
        String completionTime = null;
        if (started > 0) {
            startTime = formatTimestamp(started);

            long duration = getDuration(run);
            if (duration > 0) {
                completionTime = formatTimestamp(started + duration);
            }
        }

        KubernetesClient kubeClent = getKubernetesClient();
        String namespace = GlobalPluginConfiguration.get().getNamespace();
        NonNamespaceOperation<PipelineActivity, PipelineActivityList, DoneablePipelineActivities, Resource<PipelineActivity, DoneablePipelineActivities>> client = ClientHelper.pipelineActivityClient(kubeClent, namespace);

        String parentFullName = run.getParent().getFullName();
        String runName = parentFullName + "-" + run.getNumber();
        String name = KubernetesNames.convertToKubernetesName(runName, false);

        boolean create = false;
        PipelineActivity activity = client.withName(name).get();
        if (activity == null) {
            activity = new PipelineActivity();
            activity.setMetadata(new ObjectMetaBuilder().withName(name).build());
            create = true;
        }
        PipelineActivitySpec spec = activity.getSpec();
        if (spec == null) {
            spec = new PipelineActivitySpec();
            activity.setSpec(spec);
        }
        String oldYaml = create ? "" : toYamlOrRandom(spec);
        String status = getStatus(run);
        spec.setStatus(status);
        if (isBlank(spec.getStartedTimestamp())) {
            spec.setStartedTimestamp(startTime);
        }
        if (isBlank(spec.getCompletedTimestamp()) && Statuses.isCompleted(status)) {
            spec.setCompletedTimestamp(completionTime);
        }
        if (isBlank(spec.getPipeline())) {
            spec.setPipeline(parentFullName);
        }
        if (isBlank(spec.getBuild())) {
            spec.setBuild("" + run.getNumber());
        }

        List<StageNodeExt> stages = wfRunExt.getStages();
        if (stages != null) {
            int i = 0;
            for (StageNodeExt stage : stages) {
                String stageStatus = getStageStatus(stage.getStatus());
                StageActivityStep stageStep = getOrCreateStage(spec, i++);
                if (stageStep != null) {
                    stageStep.setStatus(stageStatus);
                    stageStep.setName(stage.getName());
                    if (isBlank(stageStep.getStartedTimestamp())) {
                        stageStep.setStartedTimestamp(formatTimestamp(stage.getStartTimeMillis()));
                    }
                    if (isBlank(stageStep.getCompletedTimestamp()) && Statuses.isCompleted(stageStatus)) {
                        stageStep.setCompletedTimestamp(formatTimestamp(stage.getStartTimeMillis() + stage.getDurationMillis()));
                    }
                }
            }
        }

        String newYaml = create ? "" : toYamlOrRandom(spec);
        if (create || !oldYaml.equals(newYaml)) {
            client.createOrReplace(activity);
            logger.log(INFO, (create ? "Created" : "Updated") + "  pipeline activity " + name);
        }
    }

    protected String toYamlOrRandom(Object data) {
        try {
            return toYaml(data);
        } catch (IOException e) {
            logger.log(WARNING, "Could not marshal Object " + data + " to YAML: " + e, e);
            return UUID.randomUUID().toString();
        }
    }

    private StageActivityStep getOrCreateStage(PipelineActivitySpec spec, int index) {
        int i = 0;
        List<PipelineActivityStep> steps = spec.getSteps();
        for (PipelineActivityStep step : steps) {
            StageActivityStep stage = step.getStage();
            if (stage != null) {
                if (i++ == index) {
                    return stage;
                }
            }
        }
        StageActivityStep answer = null;
        while (i <= index) {
            PipelineActivityStep step = new PipelineActivityStep();
            step.setKind("stage");
            StageActivityStep stage = new StageActivityStep();
            step.setStage(stage);
            steps.add(step);
            answer = stage;
            i++;
        }
        spec.setSteps(steps);
        return answer;
    }

    private String getStageStatus(StatusExt status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case ABORTED:
                return Statuses.ABORTED;
            case NOT_EXECUTED:
                return Statuses.NOT_EXECUTED;
            case SUCCESS:
                return Statuses.SUCCEEDED;
            case IN_PROGRESS:
                return Statuses.PENDING;
            case PAUSED_PENDING_INPUT:
                return Statuses.WAITING_FOR_APPROVAL;
            case FAILED:
                return Statuses.FAILED;
            case UNSTABLE:
                return Statuses.UNSTABLE;
            default:
                return "";
        }
    }

    private String getStatus(Run run) {
        if (run != null && !run.hasntStartedYet()) {
            if (run.isBuilding()) {
                return Statuses.RUNNING;
            } else {
                Result result = run.getResult();
                if (result != null) {
                    if (result.equals(Result.SUCCESS)) {
                        return Statuses.SUCCEEDED;
                    } else if (result.equals(Result.ABORTED)) {
                        return Statuses.ABORTED;
                    } else if (result.equals(Result.FAILURE)) {
                        return Statuses.FAILED;
                    } else if (result.equals(Result.UNSTABLE)) {
                        return Statuses.FAILED;
                    } else {
                        return Statuses.PENDING;
                    }
                }
            }
        }
        return "";
    }


    private long getStartTime(Run run) {
        return run.getStartTimeInMillis();
    }

    private long getDuration(Run run) {
        return run.getDuration();
    }

    /**
     * Returns true if we should poll the status of this run
     *
     * @param run the Run to test against
     * @return true if the should poll the status of this build run
     */
    protected boolean shouldPollRun(Run run) {
        return run instanceof WorkflowRun;
    }
}