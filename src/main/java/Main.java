import de.tum.ei.lkn.eces.core.Controller;
import de.tum.ei.lkn.eces.dnm.DNMSystem;
import de.tum.ei.lkn.eces.dnm.ResidualMode;
import de.tum.ei.lkn.eces.dnm.color.*;
import de.tum.ei.lkn.eces.dnm.config.ACModel;
import de.tum.ei.lkn.eces.dnm.config.BurstIncreaseModel;
import de.tum.ei.lkn.eces.dnm.config.DetServConfig;
import de.tum.ei.lkn.eces.dnm.config.costmodels.functions.Multiplication;
import de.tum.ei.lkn.eces.dnm.config.costmodels.functions.Summation;
import de.tum.ei.lkn.eces.dnm.config.costmodels.values.Constant;
import de.tum.ei.lkn.eces.dnm.config.costmodels.values.QueueDelay;
import de.tum.ei.lkn.eces.dnm.mappers.DetServConfigMapper;
import de.tum.ei.lkn.eces.dnm.proxies.DetServProxy;
import de.tum.ei.lkn.eces.graph.GraphSystem;
import de.tum.ei.lkn.eces.nbi.NBISystem;
import de.tum.ei.lkn.eces.network.Network;
import de.tum.ei.lkn.eces.network.NetworkingSystem;
import de.tum.ei.lkn.eces.network.color.DelayColoring;
import de.tum.ei.lkn.eces.network.color.QueueColoring;
import de.tum.ei.lkn.eces.network.color.RateColoring;
import de.tum.ei.lkn.eces.routing.RoutingSystem;
import de.tum.ei.lkn.eces.routing.algorithms.RoutingAlgorithm;
import de.tum.ei.lkn.eces.routing.algorithms.csp.unicast.larac.LARACAlgorithm;
import de.tum.ei.lkn.eces.routing.pathlist.LastEmbeddingColoring;
import de.tum.ei.lkn.eces.routing.pathlist.PathListColoring;
import de.tum.ei.lkn.eces.routing.pathlist.PathListSystem;
import de.tum.ei.lkn.eces.sbi.ExpectedHost;
import de.tum.ei.lkn.eces.sbi.SBISystem;
import de.tum.ei.lkn.eces.tenantmanager.TenantManagerSystem;
import de.tum.ei.lkn.eces.webgraphgui.WebGraphGuiSystem;
import de.tum.ei.lkn.eces.webgraphgui.color.ColoringSystem;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

public class Main {
    private static Logger LOG;

    private static final int GUI_PORT = 18888;
    private static final int NBI_PORT = 22222;

    private static void configureLogger() {
        configureLogger(null);
    }

    private static void configureLogger(String outputFile) {
        LOG = Logger.getLogger(Main.class);
        LOG.removeAllAppenders();
        Logger.getRootLogger().removeAllAppenders();
        if(outputFile == null)
            org.apache.log4j.BasicConfigurator.configure();
        else {
            FileAppender appender;
            try {
                appender = new FileAppender(new PatternLayout("%r [%t] %p %c %x - %m%n"), outputFile,false);
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("Impossible to open log file!");
            }

            Logger.getRootLogger().addAppender(appender);
        }

        Logger.getRootLogger().setLevel(Level.INFO);
        Logger.getLogger("de.tum.ei.lkn.eces.webgraphgui").setLevel(Level.OFF);
        Logger.getLogger("de.tum.ei.lkn.eces.core").setLevel(Level.WARN);
        Logger.getLogger("de.tum.ei.lkn.eces.graph").setLevel(Level.WARN);
        Logger.getLogger("de.tum.ei.lkn.eces.dnm").setLevel(Level.WARN);
        Logger.getLogger("io.netty").setLevel(Level.OFF);
    }

    public static void main(String[] args) throws UnknownHostException {
        init(true);
    }

    public static void init(boolean withGui) throws UnknownHostException {
        // Logger stuff
        if(LOG == null)
            configureLogger();

        // ECES systems
        Controller controller = new Controller();
        GraphSystem graphSystem = new GraphSystem(controller);
        NetworkingSystem networkingSystem = new NetworkingSystem(controller, graphSystem);

        // Mappers
        DetServConfigMapper modelingConfigMapper = new DetServConfigMapper(controller);

        // DNC
        new DNMSystem(controller);
        DetServProxy proxy = new DetServProxy(controller);
        DetServConfig modelConfig = new DetServConfig(
                ACModel.TBM,
                ResidualMode.LEAST_LATENCY,
                BurstIncreaseModel.REAL,
                true,
                new Summation(new Constant(), new Multiplication(new Constant(1000), new QueueDelay())), // 1 + delay(ms)
                new StaticTBMResourceAllocation());

        // Routing
        new RoutingSystem(controller);
        PathListSystem pathListSystem = new PathListSystem(controller);
        RoutingAlgorithm routingAlgorithm = new LARACAlgorithm(controller);
        routingAlgorithm.setProxy(proxy);
        modelConfig.initCostModel(controller);

        // Create network
        Network network = networkingSystem.createNetwork();
        modelingConfigMapper.attachComponent(network.getQueueGraph(), modelConfig);

        // GUI
        if(withGui) {
            ColoringSystem coloringSystem = new ColoringSystem(controller);
            coloringSystem.addColoringScheme(new DelayColoring(controller), "MHM/TBM assigned delay");
            coloringSystem.addColoringScheme(new QueueColoring(controller), "Queue sizes");
            coloringSystem.addColoringScheme(new RateColoring(controller), "Link rate");
            coloringSystem.addColoringScheme(new RemainingRateColoring(controller), "MHM/TBM remaining rate");
            coloringSystem.addColoringScheme(new AssignedRateColoring(controller), "MHM/TBM assigned rate");
            coloringSystem.addColoringScheme(new RemainingBufferColoring(controller), "MHM remaining buffer space");
            coloringSystem.addColoringScheme(new RemainingBufferToOverflowColoring(controller), "TBM remaining buffer space");
            coloringSystem.addColoringScheme(new AssignedBufferColoring(controller), "MHM assigned buffer space");
            coloringSystem.addColoringScheme(new RemainingDelayColoring(controller), "MHM/TBM remaining delay");
            coloringSystem.addColoringScheme(new LastEmbeddingColoring(pathListSystem), "Last embedded flow");
            coloringSystem.addColoringScheme(new PathListColoring(controller), "Amount of paths");
            new WebGraphGuiSystem(controller, coloringSystem, GUI_PORT);
        }

        // SBI
        Set<ExpectedHost> hosts = new HashSet<>();
        hosts.add(new ExpectedHost(InetAddress.getByName("kane.forschung.lkn.ei.tum.de"), "0000:04:00.0"));
        hosts.add(new ExpectedHost(InetAddress.getByName("gerrard.forschung.lkn.ei.tum.de"), "0000:04:00.0"));
        hosts.add(new ExpectedHost(InetAddress.getByName("scholes.forschung.lkn.ei.tum.de"), "0000:04:00.0"));
        hosts.add(new ExpectedHost(InetAddress.getByName("hazard.forschung.lkn.ei.tum.de"), "0000:04:00.0"));
        Set<InetAddress> switches = new HashSet<>();
        switches.add(InetAddress.getByName("leicester.forschung.lkn.ei.tum.de"));
        switches.add(InetAddress.getByName("leeds.forschung.lkn.ei.tum.de"));
        switches.add(InetAddress.getByName("watford.forschung.lkn.ei.tum.de"));
        switches.add(InetAddress.getByName("newcastle.forschung.lkn.ei.tum.de"));
        switches.add(InetAddress.getByName("tottenham.forschung.lkn.ei.tum.de"));
        switches.add(InetAddress.getByName("liverpool.forschung.lkn.ei.tum.de"));
        switches.add(InetAddress.getByName("mancity.forschung.lkn.ei.tum.de"));
        switches.add(InetAddress.getByName("westham.forschung.lkn.ei.tum.de"));
        switches.add(InetAddress.getByName("fulham.forschung.lkn.ei.tum.de"));
        switches.add(InetAddress.getByName("chelsea.forschung.lkn.ei.tum.de"));
        new SBISystem(controller, networkingSystem, network, hosts, switches, 19, 3, true);

        // Tenant Manager
        TenantManagerSystem tenantManagerSystem = new TenantManagerSystem(network, routingAlgorithm, controller);

        // NBI
        new NBISystem(tenantManagerSystem, controller, NBI_PORT);
    }
}
