package net.ssehub.kBuildCrawler.metrics;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import net.ssehub.kBuildCrawler.git.mail_parsing.FileDefect;
import net.ssehub.kernel_haven.SetUpException;
import net.ssehub.kernel_haven.metric_haven.metric_components.BlocksPerFunctionMetric;
import net.ssehub.kernel_haven.metric_haven.metric_components.CyclomaticComplexityMetric;
import net.ssehub.kernel_haven.metric_haven.metric_components.DLoC;
import net.ssehub.kernel_haven.metric_haven.metric_components.FanInOutMetric;
import net.ssehub.kernel_haven.metric_haven.metric_components.NestingDepthMetric;
import net.ssehub.kernel_haven.metric_haven.metric_components.VariablesPerFunctionMetric;
import net.ssehub.kernel_haven.metric_haven.metric_components.config.MetricSettings;
import net.ssehub.kernel_haven.metric_haven.multi_results.MetricsAggregator;
import net.ssehub.kernel_haven.metric_haven.multi_results.MultiMetricResult;
import net.ssehub.kernel_haven.util.Util;
import net.ssehub.kernel_haven.util.io.ITableCollection;
import net.ssehub.kernel_haven.util.io.ITableReader;
import net.ssehub.kernel_haven.util.io.TableCollectionReaderFactory;
import net.ssehub.kernel_haven.util.null_checks.Nullable;

/**
 * Alternative implementation of {@link KernelHavenRunner}, which runs KernelHaven in a separate process.
 * @author El-Sharkawy
 *
 */
public class KernelHavenProcessRunner extends AbstractKernelHavenRunner {
    
    private static final String KH_DIR = "kh";
    private static final String BASE_CONFIGURATION = "res/single_metric.properties";
    private static final Class<?>[] UNFILTERABLE_METRICS = {FanInOutMetric.class};
    private static final Class<?>[] FILTERABLE_METRICS = {BlocksPerFunctionMetric.class, CyclomaticComplexityMetric.class,
        DLoC.class, NestingDepthMetric.class, VariablesPerFunctionMetric.class};

    /**
     * Creates the configuration and performs the analysis in (multiple) separate processes.
     * @param sourceTree The path to the root of the linux tree to analyze, i.e., the root of the git repository
     * @param metrics The metrics to apply (either {@link #UNFILTERABLE_METRICS} or {@link #FILTERABLE_METRICS}).
     * @param defect The defect to filter the metrics (required for {@link #FILTERABLE_METRICS}).
     * @return The metric results.
     * 
     * @throws FileNotFoundException
     * @throws IOException
     */
    private List<MultiMetricResult> runAnalysis(File sourceTree, Class<?>[] metrics, @Nullable FileDefect defect,
        @Nullable List<FileDefect> defects) throws FileNotFoundException, IOException {
        
        List<MultiMetricResult> results = new ArrayList<>();
        
        // Iterate over all metric (execute them in independent processes)
        long t0 = System.currentTimeMillis();
        for (Class<?> metric : metrics) {
            String analysisName = metric.getSimpleName() + " on " + sourceTree.getName();
            System.err.println("Run: " + analysisName);
            File configFile = prepareConfiguration(sourceTree, metric, defect, defects);
            
            // Execute the process, keep track of std out stream (we log every thing to console)
            OutputStream outStream = new ByteArrayOutputStream();
            OutputStream errStream = new ByteArrayOutputStream();
            ProcessBuilder processBuilder = new ProcessBuilder("java", "-Xmx270G", "-Xmx270G", "-jar",
                "KernelHaven_withsource.jar", configFile.getAbsolutePath());
            processBuilder.directory(new File(KH_DIR));
            boolean success = Util.executeProcess(processBuilder, "MetricsRunner: " + analysisName, outStream,
                errStream, 0);

            if (success) {
                List<Path> list = new ArrayList<>();
                Files.newDirectoryStream(Paths.get(KH_DIR, "output"), p -> p.toFile().getName().endsWith(".csv"))
                    .forEach(list::add);
                System.err.println(list.size() + " files produced for " + analysisName + ", start merging them");
                
                int fileIndex = 1;
                for (Path path : list) {
                    System.err.println("Read file: " + fileIndex++);
                    File file = path.toFile();
                    try (ITableCollection csvCollection = TableCollectionReaderFactory.INSTANCE.openFile(file)) {
                        String firstAndOnlyTable = csvCollection.getTableNames().iterator().next();
                        try (ITableReader reader = csvCollection.getReader(firstAndOnlyTable)) {
                            // Read Header
                            final String[] header = reader.readNextRow();
                            String[] content = null;
                            while ((content = reader.readNextRow()) != null) {
                                MultiMetricResult result = new MultiMetricResult(header, content);
                                results.add(result);
                            }
                        }
                    }
                    
                    // Delete temporarily created output of KernelHaven
                    file.delete();
                }
                
            } else {
                LOGGER.logError2("Could not execute ", analysisName, ", cause: " + outStream.toString());
            }
            
            configFile.delete();
        }
        long delta = System.currentTimeMillis() - t0;
        // See: https://stackoverflow.com/a/9027379
        String elapsedTime = String.format("%02d:%02d:%02d", 
            TimeUnit.MILLISECONDS.toHours(delta),
            TimeUnit.MILLISECONDS.toMinutes(delta) -  
            TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(delta)),
            TimeUnit.MILLISECONDS.toSeconds(delta) - 
            TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(delta)));
        System.err.println(metrics.length + " metric analyses (+ merging " + results.size() + " results) took "
            + elapsedTime);
        
        return results;
    }
    
    @Override
    protected List<MultiMetricResult> runNonFilterableMetrics(File sourceTree, List<FileDefect> defects)
        throws IOException, SetUpException {
        return runAnalysis(sourceTree, UNFILTERABLE_METRICS, null, defects);
    }

    @Override
    protected List<MultiMetricResult> runLineFilteredMetrics(File sourceTree, FileDefect defect)
        throws IOException, SetUpException {
        
        return runAnalysis(sourceTree, FILTERABLE_METRICS, defect, null);
    }
    
    private File prepareConfiguration(File sourceTree, Class<?> metricClass, @Nullable FileDefect defect,
        @Nullable List<FileDefect> defects) throws FileNotFoundException, IOException {
        
        // Read configuration template
        // Stores properties in a sorted order (for debugging issues only):
        // based on: https://stackoverflow.com/a/17011319
        Properties props = new Properties() {
            /**
             * Generated ID
             */
            private static final long serialVersionUID = -5477130775592634228L;

            @Override
            public synchronized Enumeration<Object> keys() {
                return Collections.enumeration(new TreeSet<Object>(super.keySet()));
            }
        };
        props.load(new FileReader(new File(BASE_CONFIGURATION)));
        
        // Adapt configuration based to current checkout and metric to execute
        props.setProperty("source_tree", sourceTree.getAbsolutePath());
        props.setProperty("analysis.metrics_runner.metrics_class", metricClass.getCanonicalName());
        if (null != defect) {
            String file = defect.getPath() + defect.getFile();
            props.setProperty("code.extractor.files", file);
            props.setProperty("analysis.code_function.line", String.valueOf(defect.getLine()));            
        }
        if (null != defects) {
            StringJoiner sj = new StringJoiner(",");
            for (FileDefect fileDefect : defects) {
                String path = fileDefect.getPath();
                if (!path.endsWith("/")) {
                    // Should not be necessary
                    path += '/';
                }
                path += fileDefect.getFile();
                sj.add(path);
            }
            props.setProperty(MetricSettings.FILTER_BY_FILES.getKey(), sj.toString());
        }
        
        // Save temporary configuration and return file (required as a parameter, later)
        File tmpProperties = File.createTempFile("SingleMetricAnalysis", ".properties");
        tmpProperties.deleteOnExit();
        try (OutputStream output = new FileOutputStream(tmpProperties)) {
            props.store(output, null);
        }
        
        return tmpProperties;
    }

}