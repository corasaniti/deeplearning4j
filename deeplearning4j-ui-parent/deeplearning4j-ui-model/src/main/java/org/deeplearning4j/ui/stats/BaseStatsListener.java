package org.deeplearning4j.ui.stats;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.bytedeco.javacpp.Pointer;
import org.deeplearning4j.api.storage.StatsStorageRouter;
import org.deeplearning4j.api.storage.StorageMetaData;
import org.deeplearning4j.api.storage.listener.RoutingIterationListener;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.ui.stats.api.*;
import org.deeplearning4j.ui.stats.impl.DefaultStatsInitializationConfiguration;
import org.deeplearning4j.ui.stats.impl.DefaultStatsUpdateConfiguration;
import org.deeplearning4j.util.UIDProvider;
import org.nd4j.linalg.api.buffer.util.DataTypeUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;

import java.io.InputStream;
import java.io.Serializable;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Constructor;
import java.util.*;

/**
 * BaseStatsListener: a general purpose listener for collecting and reporting system and model information.
 *
 * Serves as a base for different ways of storing the collected data
 *
 * @author Alex Black
 */
@Slf4j
public abstract class BaseStatsListener implements RoutingIterationListener {
    public static final String TYPE_ID = "StatsListener";

    private enum StatType {Mean, Stdev, MeanMagnitude}

    private StatsStorageRouter router;
    private final StatsInitializationConfiguration initConfig;
    private StatsUpdateConfiguration updateConfig;
    private String sessionID;
    private String workerID;

    private transient List<GarbageCollectorMXBean> gcBeans;
    private Map<String, Pair<Long, Long>> gcStatsAtLastReport;

    private Map<String, INDArray> activationsMap;
    private Map<String, INDArray> gradientsPreUpdateMap = new HashMap<>();

    //NOTE: may have multiple models, due to multiple pretrain layers all using the same StatsListener
    private List<ModelInfo> modelInfos = new ArrayList<>();

    private static class ModelInfo implements Serializable {
        private final Model model;
        private long initTime;
        private long lastReportTime = -1;
        private int lastReportIteration = -1;
        private int examplesSinceLastReport = 0;
        private int minibatchesSinceLastReport = 0;

        private long totalExamples = 0;
        private long totalMinibatches = 0;

        private int iterCount = 0;

        private ModelInfo(Model model){
            this.model = model;
        }
    }

    private ModelInfo getModelInfo(Model model){
        ModelInfo mi = null;
        for(ModelInfo m : modelInfos){
            if(m.model == model){
                mi = m;
                break;
            }
        }
        if(mi == null){
            mi = new ModelInfo(model);
            modelInfos.add(mi);
        }
        return mi;
    }

    /**
     * Create a StatsListener with network information collected at every iteration. Equivalent to {@link #StatsListener(StatsStorageRouter, int)}
     * with {@code listenerFrequency == 1}
     *
     * @param router Where/how to store the calculated stats. For example, {@link org.deeplearning4j.ui.storage.InMemoryStatsStorage} or
     *               {@link org.deeplearning4j.ui.storage.FileStatsStorage}
     */
    public BaseStatsListener(StatsStorageRouter router) {
        this(router, null, null, null, null);
    }

    /**
     * Create a StatsListener with network information collected every n >= 1 time steps
     *
     * @param router            Where/how to store the calculated stats. For example, {@link org.deeplearning4j.ui.storage.InMemoryStatsStorage} or
     *                          {@link org.deeplearning4j.ui.storage.FileStatsStorage}
     * @param listenerFrequency Frequency with which to collect stats information
     */
    public BaseStatsListener(StatsStorageRouter router, int listenerFrequency) {
        this(router, null, new DefaultStatsUpdateConfiguration.Builder().reportingFrequency(listenerFrequency).build(), null, null);
    }

    public BaseStatsListener(StatsStorageRouter router, StatsInitializationConfiguration initConfig, StatsUpdateConfiguration updateConfig,
                             String sessionID, String workerID) {
        this.router = router;
        if (initConfig == null) {
            this.initConfig = new DefaultStatsInitializationConfiguration(true, true, true);
        } else {
            this.initConfig = initConfig;
        }
        if (updateConfig == null) {
            this.updateConfig = new DefaultStatsUpdateConfiguration.Builder().build();
        } else {
            this.updateConfig = updateConfig;
        }
        if (sessionID == null) {
            //TODO handle syncing session IDs across different listeners in the same model...
            this.sessionID = UUID.randomUUID().toString();
        } else {
            this.sessionID = sessionID;
        }
        if (workerID == null) {
            this.workerID = UIDProvider.getJVMUID() + "_" + Thread.currentThread().getId();
        } else {
            this.workerID = workerID;
        }
    }

    public abstract StatsInitializationReport getNewInitializationReport();

    public abstract StatsReport getNewStatsReport();

//    public abstract StorageMetaData getNewStorageMetaData();
    public abstract StorageMetaData getNewStorageMetaData(long initTime, String sessionID, String workerID);
//                                                          Class<? extends StatsInitializationReport> initializationReportClass,
//                                                          Class<? extends StatsReport> statsReportClass);
    //new SbeStorageMetaData(initTime, getSessionID(model), TYPE_ID, workerID, SbeStatsInitializationReport.class, SbeStatsReport.class);


    public StatsInitializationConfiguration getInitConfig() { return initConfig; }

    public StatsUpdateConfiguration getUpdateConfig() { return updateConfig; }

    public void setUpdateConfig(StatsUpdateConfiguration newConfig) { this.updateConfig = newConfig; }

    @Override
    public void setStorageRouter(StatsStorageRouter router) {
        this.router = router;
    }

    @Override
    public StatsStorageRouter getStorageRouter() {
        return router;
    }

    @Override
    public void setWorkerID(String workerID) {
        this.workerID = workerID;
    }

    @Override
    public String getWorkerID() {
        return workerID;
    }

    @Override
    public void setSessionID(String sessionID) {
        this.sessionID = sessionID;
    }

    @Override
    public String getSessionID() {
        return sessionID;
    }

    private String getSessionID(Model model){
        if(model instanceof MultiLayerNetwork || model instanceof ComputationGraph) return sessionID;
        if(model instanceof Layer){
            //Keep in mind MultiLayerNetwork implements Layer also...
            Layer l = (Layer)model;
            int layerIdx = l.getIndex();
            return sessionID + "_layer" + layerIdx;
        }
        return sessionID;   //Should never happen
    }

    @Override
    public boolean invoked() {
        return modelInfos.size() > 0;
    }

    @Override
    public void invoke() {

    }

    @Override
    public void onEpochStart(Model model) {

    }

    @Override
    public void onEpochEnd(Model model) {

    }

    @Override
    public void onForwardPass(Model model, List<INDArray> activations) {
        int iterCount = getModelInfo(model).iterCount;
        if (storeActivations() && (iterCount == 0 || iterCount % updateConfig.reportingFrequency() == 0)) {
            //Assumption: we have input, layer 0, layer 1, ...
            activationsMap = new HashMap<>();
            int count = 0;
            for (INDArray arr : activations) {
                String layerName = (count == 0 ? "input" : String.valueOf(count - 1));
                activationsMap.put(layerName, arr);
                count++;
            }
        }
    }

    @Override
    public void onForwardPass(Model model, Map<String, INDArray> activations) {
        int iterCount = getModelInfo(model).iterCount;
        if (storeActivations() && updateConfig.reportingFrequency() > 0 && (iterCount == 0 || iterCount % updateConfig.reportingFrequency() == 0)) {
            activationsMap = activations;
        }
    }

    @Override
    public void onGradientCalculation(Model model) {
        int iterCount = getModelInfo(model).iterCount;
        if (storeGradients() && updateConfig.reportingFrequency() > 0 && (iterCount == 0 || iterCount % updateConfig.reportingFrequency() == 0)) {
            Gradient g = model.gradient();
            gradientsPreUpdateMap.clear();
            for (Map.Entry<String, INDArray> entry : g.gradientForVariable().entrySet()) {
                gradientsPreUpdateMap.put(entry.getKey(), entry.getValue().dup());    //Need to clone: will be modified (updated) in-place soon...
            }
        }
    }

    private boolean storeActivations() {
        return updateConfig.collectMean(StatsType.Activations) || updateConfig.collectStdev(StatsType.Activations)
                || updateConfig.collectMeanMagnitudes(StatsType.Activations) || updateConfig.collectHistograms(StatsType.Activations);
    }

    private boolean storeGradients() {
        return updateConfig.collectMean(StatsType.Gradients) || updateConfig.collectStdev(StatsType.Gradients)
                || updateConfig.collectMeanMagnitudes(StatsType.Gradients) || updateConfig.collectHistograms(StatsType.Gradients);
    }

    @Override
    public void onBackwardPass(Model model) {
        //No op
    }

    @Override
    public void iterationDone(Model model, int iteration) {
        StatsUpdateConfiguration config = updateConfig;

        ModelInfo modelInfo = getModelInfo(model);
        boolean backpropParamsOnly = backpropParamsOnly(model);

        long currentTime = getTime();
        if (modelInfo.iterCount == 0) {
            modelInfo.initTime = currentTime;
            doInit(model);
        }

        if (config.collectPerformanceStats()) {
            updateExamplesMinibatchesCounts(model);
        }

        if (config.reportingFrequency() > 1 && (iteration == 0 || iteration % config.reportingFrequency() != 0)) {
            modelInfo.iterCount = iteration;
            return;
        }

        StatsReport report = getNewStatsReport();
        report.reportIDs(getSessionID(model), TYPE_ID, workerID, System.currentTimeMillis()); //TODO support NTP time

        //--- Performance and System Stats ---
        if (config.collectPerformanceStats()) {
            //Stats to collect: total runtime, total examples, total minibatches, iterations/second, examples/second
            double examplesPerSecond;
            double minibatchesPerSecond;
            if (modelInfo.iterCount == 0) {
                //Not possible to work out perf/second: first iteration...
                examplesPerSecond = 0.0;
                minibatchesPerSecond = 0.0;
            } else {
                long deltaTimeMS = currentTime - modelInfo.lastReportTime;
                examplesPerSecond = 1000.0 * modelInfo.examplesSinceLastReport / deltaTimeMS;
                minibatchesPerSecond = 1000.0 * modelInfo.minibatchesSinceLastReport / deltaTimeMS;
            }
            long totalRuntimeMS = currentTime - modelInfo.initTime;
            report.reportPerformance(totalRuntimeMS, modelInfo.totalExamples, modelInfo.totalMinibatches, examplesPerSecond, minibatchesPerSecond);

            modelInfo.examplesSinceLastReport = 0;
            modelInfo.minibatchesSinceLastReport = 0;
        }

        if (config.collectMemoryStats()) {

            Runtime runtime = Runtime.getRuntime();
            long jvmTotal = runtime.totalMemory();
            long jvmMax = runtime.maxMemory();

            //Off-heap memory
            long offheapTotal = Pointer.totalBytes();
            long offheapMax = Pointer.maxBytes();

            //GPU
            long[] gpuCurrentBytes = null;
            long[] gpuMaxBytes = null;
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            int nDevices = nativeOps.getAvailableDevices();
            if (nDevices > 0) {
                gpuCurrentBytes = new long[nDevices];
                gpuMaxBytes = new long[nDevices];
                for (int i = 0; i < nDevices; i++) {
                    try {
                        Pointer p = getDevicePointer(i);
                        if (p == null) {
                            gpuMaxBytes[i] = 0;
                            gpuCurrentBytes[i] = 0;
                        } else {
                            gpuMaxBytes[i] = nativeOps.getDeviceTotalMemory(p);
                            gpuCurrentBytes[i] = gpuMaxBytes[i] - nativeOps.getDeviceFreeMemory(p);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            report.reportMemoryUse(jvmTotal, jvmMax, offheapTotal, offheapMax, gpuCurrentBytes, gpuMaxBytes);
        }

        if (config.collectGarbageCollectionStats()) {
            if (modelInfo.lastReportIteration == -1 || gcBeans == null) {
                //Haven't reported GC stats before...
                gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
                gcStatsAtLastReport = new HashMap<>();
                for (GarbageCollectorMXBean bean : gcBeans) {
                    long count = bean.getCollectionCount();
                    long timeMs = bean.getCollectionTime();
                    gcStatsAtLastReport.put(bean.getName(), new Pair<>(count, timeMs));
                }
            } else {
                for (GarbageCollectorMXBean bean : gcBeans) {
                    long count = bean.getCollectionCount();
                    long timeMs = bean.getCollectionTime();
                    Pair<Long, Long> lastStats = gcStatsAtLastReport.get(bean.getName());
                    long deltaGCCount = count - lastStats.getFirst();
                    long deltaGCTime = timeMs - lastStats.getSecond();

                    lastStats.setFirst(count);
                    lastStats.setSecond(timeMs);
                    report.reportGarbageCollection(bean.getName(), (int) deltaGCCount, (int) deltaGCTime);
                }
            }
        }

        //--- General ---
        report.reportScore(model.score());  //Always report score

        if (config.collectLearningRates()) {
            Map<String, Double> lrs = new HashMap<>();
            if (model instanceof MultiLayerNetwork) {
                //Need to append "0_", "1_" etc to param names from layers...
                int layerIdx = 0;
                for (Layer l : ((MultiLayerNetwork) model).getLayers()) {
                    NeuralNetConfiguration conf = l.conf();
                    Map<String, Double> layerLrs = conf.getLearningRateByParam();
                    Set<String> backpropParams = l.paramTable(true).keySet();
                    for (Map.Entry<String, Double> entry : layerLrs.entrySet()) {
                        if(!backpropParams.contains(entry.getKey())) continue;  //Skip pretrain params
                        lrs.put(layerIdx + "_" + entry.getKey(), entry.getValue());
                    }
                    layerIdx++;
                }
            } else if (model instanceof ComputationGraph) {
                for (Layer l : ((ComputationGraph) model).getLayers()) {
                    //Need to append layer name
                    NeuralNetConfiguration conf = l.conf();
                    Map<String, Double> layerLrs = conf.getLearningRateByParam();
                    String layerName = conf.getLayer().getLayerName();
                    Set<String> backpropParams = l.paramTable(true).keySet();
                    for (Map.Entry<String, Double> entry : layerLrs.entrySet()) {
                        if(!backpropParams.contains(entry.getKey())) continue;  //Skip pretrain params
                        lrs.put(layerName + "_" + entry.getKey(), entry.getValue());
                    }
                }
            } else if(model instanceof Layer){
                Layer l = (Layer)model;
                Map<String,Double> map = l.conf().getLearningRateByParam();
                lrs.putAll(map);
            }
            report.reportLearningRates(lrs);
        }


        //--- Histograms ---

        if (config.collectHistograms(StatsType.Parameters)) {
            Map<String, Histogram> paramHistograms = getHistograms(model.paramTable(backpropParamsOnly), config.numHistogramBins(StatsType.Parameters));
            report.reportHistograms(StatsType.Parameters, paramHistograms);
        }

        if (config.collectHistograms(StatsType.Gradients)) {
            Map<String, Histogram> gradientHistograms = getHistograms(gradientsPreUpdateMap, config.numHistogramBins(StatsType.Gradients));
            report.reportHistograms(StatsType.Gradients, gradientHistograms);
        }

        if (config.collectHistograms(StatsType.Updates)) {
            Map<String, Histogram> updateHistograms = getHistograms(model.gradient().gradientForVariable(), config.numHistogramBins(StatsType.Updates));
            report.reportHistograms(StatsType.Updates, updateHistograms);
        }

        if (config.collectHistograms(StatsType.Activations)) {
            Map<String, Histogram> activationHistograms = getHistograms(activationsMap, config.numHistogramBins(StatsType.Activations));
            report.reportHistograms(StatsType.Activations, activationHistograms);
        }


        //--- Summary Stats: Mean, Variance, Mean Magnitudes ---

        if (config.collectMean(StatsType.Parameters)) {
            Map<String, Double> meanParams = calculateSummaryStats(model.paramTable(backpropParamsOnly), StatType.Mean);
            report.reportMean(StatsType.Parameters, meanParams);
        }

        if (config.collectMean(StatsType.Gradients)) {
            Map<String, Double> meanGradients = calculateSummaryStats(gradientsPreUpdateMap, StatType.Mean);
            report.reportMean(StatsType.Gradients, meanGradients);
        }

        if (config.collectMean(StatsType.Updates)) {
            Map<String, Double> meanUpdates = calculateSummaryStats(model.gradient().gradientForVariable(), StatType.Mean);
            report.reportMean(StatsType.Updates, meanUpdates);
        }

        if (config.collectMean(StatsType.Activations)) {
            Map<String, Double> meanActivations = calculateSummaryStats(activationsMap, StatType.Mean);
            report.reportMean(StatsType.Activations, meanActivations);
        }


        if (config.collectStdev(StatsType.Parameters)) {
            Map<String, Double> stdevParams = calculateSummaryStats(model.paramTable(backpropParamsOnly), StatType.Stdev);
            report.reportStdev(StatsType.Parameters, stdevParams);
        }

        if (config.collectStdev(StatsType.Gradients)) {
            Map<String, Double> stdevGradient = calculateSummaryStats(gradientsPreUpdateMap, StatType.Stdev);
            report.reportStdev(StatsType.Gradients, stdevGradient);
        }

        if (config.collectStdev(StatsType.Updates)) {
            Map<String, Double> stdevUpdates = calculateSummaryStats(model.gradient().gradientForVariable(), StatType.Stdev);
            report.reportStdev(StatsType.Updates, stdevUpdates);
        }

        if (config.collectStdev(StatsType.Activations)) {
            Map<String, Double> stdevActivations = calculateSummaryStats(activationsMap, StatType.Stdev);
            report.reportStdev(StatsType.Activations, stdevActivations);
        }


        if (config.collectMeanMagnitudes(StatsType.Parameters)) {
            Map<String, Double> meanMagParams = calculateSummaryStats(model.paramTable(backpropParamsOnly), StatType.MeanMagnitude);
            report.reportMeanMagnitudes(StatsType.Parameters, meanMagParams);
        }

        if (config.collectMeanMagnitudes(StatsType.Gradients)) {
            Map<String, Double> meanMagGradients = calculateSummaryStats(gradientsPreUpdateMap, StatType.MeanMagnitude);
            report.reportMeanMagnitudes(StatsType.Gradients, meanMagGradients);
        }

        if (config.collectMeanMagnitudes(StatsType.Updates)) {
            Map<String, Double> meanMagUpdates = calculateSummaryStats(model.gradient().gradientForVariable(), StatType.MeanMagnitude);
            report.reportMeanMagnitudes(StatsType.Updates, meanMagUpdates);
        }

        if (config.collectMeanMagnitudes(StatsType.Activations)) {
            Map<String, Double> meanMagActivations = calculateSummaryStats(activationsMap, StatType.MeanMagnitude);
            report.reportMeanMagnitudes(StatsType.Activations, meanMagActivations);
        }


        long endTime = getTime();
        report.reportStatsCollectionDurationMS((int) (endTime - currentTime));    //Amount of time required to alculate all histograms, means etc.
        modelInfo.lastReportTime = currentTime;
        modelInfo.lastReportIteration = iteration;
        report.reportIterationCount(iteration);

        this.router.putUpdate(report);

        modelInfo.iterCount = iteration;
        activationsMap = null;
    }

    private long getTime() {
        //Abstraction to allow NTP to be plugged in later...
        return System.currentTimeMillis();
    }

    private void doInit(Model model) {
        boolean backpropParamsOnly = backpropParamsOnly(model);
        long initTime = System.currentTimeMillis(); //TODO support NTP
        StatsInitializationReport initReport = getNewInitializationReport();
        initReport.reportIDs(getSessionID(model), TYPE_ID, workerID, initTime);

        if (initConfig.collectSoftwareInfo()) {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

            String arch = osBean.getArch();
            String osName = osBean.getName();
            String jvmName = runtime.getVmName();
            String jvmVersion = System.getProperty("java.version");
            String jvmSpecVersion = runtime.getSpecVersion();

            String nd4jBackendClass = Nd4j.getNDArrayFactory().getClass().getName();
            String nd4jDataTypeName = DataTypeUtil.getDtypeFromContext().name();

            String hostname = System.getenv("COMPUTERNAME");
            if (hostname == null || hostname.isEmpty()) {
                try {
                    Process proc = Runtime.getRuntime().exec("hostname");
                    try (InputStream stream = proc.getInputStream()) {
                        hostname = IOUtils.toString(stream);
                    }
                } catch (Exception e) {
                }
            }

            Properties p = Nd4j.getExecutioner().getEnvironmentInformation();
            Map<String, String> envInfo = new HashMap<>();
            for (Map.Entry<Object, Object> e : p.entrySet()) {
                Object v = e.getValue();
                String value = (v == null ? "" : v.toString());
                envInfo.put(e.getKey().toString(), value);
            }

            initReport.reportSoftwareInfo(arch, osName, jvmName, jvmVersion, jvmSpecVersion,
                    nd4jBackendClass, nd4jDataTypeName, hostname, UIDProvider.getJVMUID(), envInfo);
        }

        if (initConfig.collectHardwareInfo()) {
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            int nDevices = nativeOps.getAvailableDevices();

            long[] deviceTotalMem = null;
            String[] deviceDescription = null;  //TODO
            if (nDevices > 0) {
                deviceTotalMem = new long[nDevices];
                deviceDescription = new String[nDevices];
                for (int i = 0; i < nDevices; i++) {
                    try {
                        Pointer p = getDevicePointer(i);
                        if (p == null) {
                            deviceTotalMem[i] = 0;
                            deviceDescription[i] = "Device(" + i + ")";
                        } else {
                            deviceTotalMem[i] = nativeOps.getDeviceTotalMemory(p);
                            deviceDescription[i] = nativeOps.getDeviceName(p);
                            if (nDevices > 1) {
                                deviceDescription[i] = deviceDescription[i] + " (" + i + ")";
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Error getting device info", e);
                    }
                }
            }
            long jvmMaxMemory = Runtime.getRuntime().maxMemory();
            long offheapMaxMemory = Pointer.maxBytes();

            initReport.reportHardwareInfo(availableProcessors, nDevices, jvmMaxMemory, offheapMaxMemory, deviceTotalMem,
                    deviceDescription, UIDProvider.getHardwareUID());
        }

        if (initConfig.collectModelInfo()) {
            String jsonConf;
            int numLayers;
            int numParams;
            if (model instanceof MultiLayerNetwork) {
                MultiLayerNetwork net = ((MultiLayerNetwork) model);
                jsonConf = net.getLayerWiseConfigurations().toJson();
                numLayers = net.getnLayers();
                numParams = net.numParams();
            } else if (model instanceof ComputationGraph) {
                ComputationGraph cg = ((ComputationGraph) model);
                jsonConf = cg.getConfiguration().toJson();
                numLayers = cg.getNumLayers();
                numParams = cg.numParams();
            } else if (model instanceof Layer ){
                Layer l = (Layer)model;
                jsonConf = l.conf().toJson();
                numLayers = 1;
                numParams = l.numParams();
            } else {
                throw new RuntimeException("Invalid model: Expected MultiLayerNetwork or ComputationGraph. Got: "
                        + (model == null ? null : model.getClass()));
            }

            Map<String, INDArray> paramMap = model.paramTable(backpropParamsOnly);
            String[] paramNames = new String[paramMap.size()];
            int i = 0;
            for (String s : paramMap.keySet()) {      //Assuming sensible iteration order - LinkedHashMaps are used in MLN/CG for example
                paramNames[i++] = s;
            }

            initReport.reportModelInfo(model.getClass().getName(), jsonConf, paramNames, numLayers, numParams);
        }

        StorageMetaData meta = getNewStorageMetaData(initTime, getSessionID(model), workerID);

        router.putStorageMetaData(meta);
        router.putStaticInfo(initReport);   //TODO error handling
    }

    private Map<Integer, Pointer> devPointers = new HashMap<>();

    private synchronized Pointer getDevicePointer(int device) {
        if (devPointers.containsKey(device)) {
            return devPointers.get(device);
        }
        try {
            Class<?> c = Class.forName("org.nd4j.jita.allocator.pointers.CudaPointer");
            Constructor<?> constructor = c.getConstructor(long.class);
            Pointer p = (Pointer) constructor.newInstance((long) device);
            devPointers.put(device, p);
            return p;
        } catch (Throwable t) {
            devPointers.put(device, null);  //Stops attempting the failure again later...
            return null;
        }
    }

    private void updateExamplesMinibatchesCounts(Model model) {
        ModelInfo modelInfo = getModelInfo(model);
        int examplesThisMinibatch = 0;
        if (model instanceof MultiLayerNetwork) {
            examplesThisMinibatch = ((MultiLayerNetwork) model).batchSize();
        } else if (model instanceof ComputationGraph) {
            examplesThisMinibatch = ((ComputationGraph) model).batchSize();
        } else if (model instanceof Layer) {
            examplesThisMinibatch = ((Layer) model).getInputMiniBatchSize();
        }
        modelInfo.examplesSinceLastReport += examplesThisMinibatch;
        modelInfo.totalExamples += examplesThisMinibatch;
        modelInfo.minibatchesSinceLastReport++;
        modelInfo.totalMinibatches++;
    }

    private boolean backpropParamsOnly(Model model){
        //For pretrain layers (VAE, RBM) we *do* want pretrain params also; for MLN and CG we only want backprop params
        // as we only have backprop gradients
        return model instanceof MultiLayerNetwork || model instanceof ComputationGraph;
    }

    private static Map<String, Double> calculateSummaryStats(Map<String, INDArray> source, StatType statType) {
        Map<String, Double> out = new LinkedHashMap<>();

        if(source == null) return out;

        for (Map.Entry<String, INDArray> entry : source.entrySet()) {
            String name = entry.getKey();
            double value;
            switch (statType) {
                case Mean:
                    value = entry.getValue().meanNumber().doubleValue();
                    break;
                case Stdev:
                    value = entry.getValue().stdNumber().doubleValue();
                    break;
                case MeanMagnitude:
                    value = entry.getValue().norm1Number().doubleValue() / entry.getValue().length();
                    break;
                default:
                    throw new RuntimeException();   //Should never happen
            }
            out.put(name, value);
        }
        return out;
    }

    private static Map<String, Histogram> getHistograms(Map<String, INDArray> map, int nBins) {
        Map<String, Histogram> out = new LinkedHashMap<>();

        if(map == null) return out;

        for (Map.Entry<String, INDArray> entry : map.entrySet()) {

            org.nd4j.linalg.api.ops.impl.transforms.Histogram hOp = new org.nd4j.linalg.api.ops.impl.transforms.Histogram(entry.getValue(), nBins);
            Nd4j.getExecutioner().exec(hOp);

            INDArray bins = hOp.z();
            int[] count = new int[nBins];
            for (int i = 0; i < bins.length(); i++) {
                count[i] = (int) bins.getDouble(i);
            }

            double min = entry.getValue().minNumber().doubleValue();
            double max = entry.getValue().maxNumber().doubleValue();

            Histogram h = new Histogram(min, max, nBins, count);

            out.put(entry.getKey(), h);
        }
        return out;
    }

    @Override
    public abstract BaseStatsListener clone();
}
