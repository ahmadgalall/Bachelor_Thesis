import java.net.Socket;
import java.util.ArrayList;
import java.io.*;

import de.tum.ei.lkn.eces.core.Controller;
import de.tum.ei.lkn.eces.core.MapperSpace;
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
import de.tum.ei.lkn.eces.network.Host;
import de.tum.ei.lkn.eces.network.Network;
import de.tum.ei.lkn.eces.network.NetworkingSystem;
import de.tum.ei.lkn.eces.network.color.DelayColoring;
import de.tum.ei.lkn.eces.network.color.QueueColoring;
import de.tum.ei.lkn.eces.network.color.RateColoring;
import de.tum.ei.lkn.eces.routing.RoutingSystem;
import de.tum.ei.lkn.eces.routing.algorithms.RoutingAlgorithm;
import de.tum.ei.lkn.eces.routing.algorithms.csp.unicast.larac.LARACAlgorithm;
import de.tum.ei.lkn.eces.routing.mappers.PathMapper;
import de.tum.ei.lkn.eces.routing.pathlist.LastEmbeddingColoring;
import de.tum.ei.lkn.eces.routing.pathlist.PathListColoring;
import de.tum.ei.lkn.eces.routing.pathlist.PathListSystem;
import de.tum.ei.lkn.eces.routing.responses.Path;
import de.tum.ei.lkn.eces.tenantmanager.Flow;
import de.tum.ei.lkn.eces.tenantmanager.Tenant;
import de.tum.ei.lkn.eces.tenantmanager.TenantManagerSystem;
import de.tum.ei.lkn.eces.tenantmanager.VirtualMachine;
import de.tum.ei.lkn.eces.tenantmanager.exceptions.TenantManagerException;
import de.tum.ei.lkn.eces.tenantmanager.rerouting.CostIncreaseTypes;
import de.tum.ei.lkn.eces.tenantmanager.rerouting.FlowSelectionTypes;
import de.tum.ei.lkn.eces.tenantmanager.rerouting.LimitReroutingTypes;
import de.tum.ei.lkn.eces.tenantmanager.rerouting.SortFlowTypes;
import de.tum.ei.lkn.eces.tenantmanager.rerouting.components.ReroutingConfiguration;
import de.tum.ei.lkn.eces.tenantmanager.rerouting.mappers.ReroutingConfigurationMapper;
import de.tum.ei.lkn.eces.topologies.networktopologies.FatTree;
import de.tum.ei.lkn.eces.webgraphgui.color.ColoringSystem;
//import jdk.internal.misc.VM;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;

public class Simulation {
    static ArrayList<ArrayList<String>> AllFlows= new ArrayList<ArrayList<String>>() ;


    public static final String REMOTE_HOST = "";
    public static final int REMOTE_PORT = 65432;
    private static Logger LOG;

    private static final int GUI_PORT = 18888;

    private static void configureLogger() {
        configureLogger(null);
    }

    private static void configureLogger(String outputFile) {
        LOG = Logger.getLogger(Simulation.class);
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
        Logger.getLogger("de.tum.ei.lkn.eces.tenantmanager.kspsystem").setLevel(Level.WARN);
        Logger.getLogger("de.tum.ei.lkn.eces.core").setLevel(Level.WARN);
        Logger.getLogger("de.tum.ei.lkn.eces.dnm").setLevel(Level.WARN);
        Logger.getLogger("io.netty").setLevel(Level.OFF);
    }

    public static void main(String[] args) {
        try {
            init(true);

        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static void init(boolean withGui) throws IOException {
        // Logger stuff
        if (LOG == null)
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

        // GUI
        if (withGui) {
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
            //    new WebGraphGuiSystem(controller, coloringSystem, GUI_PORT);
        }


        // Create network
        FatTree topology;
        Socket client = new Socket("", 5064);
        BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream()));
        PrintWriter toServer = new PrintWriter(client.getOutputStream(), true);

        int kfatTree = Integer.parseInt(input.readLine());
        int nHostMultiplier = Integer.parseInt(input.readLine());
        double edgeLinkRate = Double.parseDouble(input.readLine());
        double aggregationLinkRate = Double.parseDouble(input.readLine());
        double coreLinkRate = Double.parseDouble(input.readLine());
        double propagationDelay = Double.parseDouble(input.readLine());
        double hostQueueSize = Double.parseDouble(input.readLine());
        int numOfQueuesPerSwitch = Integer.parseInt(input.readLine());
        double switchQueueSize = Double.parseDouble(input.readLine());

        System.out.println(kfatTree);
        System.out.println(nHostMultiplier);
        System.out.println(edgeLinkRate);
        System.out.println(aggregationLinkRate);
        System.out.println(coreLinkRate);
        System.out.println(propagationDelay);
        System.out.println(hostQueueSize);
        System.out.println(numOfQueuesPerSwitch);
        System.out.println(switchQueueSize);

        // Reading k for fat tree construction
        try (MapperSpace ms = controller.startMapperSpace()) {
            double[] queueSizes = new double[numOfQueuesPerSwitch];
            Arrays.fill(queueSizes, switchQueueSize);
            topology = new FatTree(networkingSystem, kfatTree, nHostMultiplier, edgeLinkRate, aggregationLinkRate, coreLinkRate, propagationDelay, queueSizes, hostQueueSize);

            Network network = topology.getNetwork();
            modelingConfigMapper.attachComponent(network.getQueueGraph(), modelConfig);
        }

        // Tenant Manager
        ReroutingConfigurationMapper reroutingConfigurationMapper = new ReroutingConfigurationMapper(controller);
        reroutingConfigurationMapper.attachComponent(topology.getNetwork().getEntity(), new ReroutingConfiguration(FlowSelectionTypes.ALL_SHORTEST_PATHS, SortFlowTypes.COMMON_EDGES_SORT, CostIncreaseTypes.PHYSICAL_LINK_INCREASE, LimitReroutingTypes.ABSOLUTE, 10));
        TenantManagerSystem tenantManagerSystem = new TenantManagerSystem(topology, routingAlgorithm, controller);


        // Creating a tenant, 2 VMs, and a flow
        ArrayList<Path> AllUpdatedPaths = new ArrayList<Path>();
        try {
            Tenant firstTenant = tenantManagerSystem.createTenant("Tenant1");
            ArrayList<Path> paths = new ArrayList<Path>();


            int i = 1;
            String numOfVms = input.readLine();
            System.out.println(numOfVms);
            int numOfVmsAsInteger = Integer.parseInt(numOfVms);
            System.out.println(numOfVmsAsInteger);
            ArrayList<VirtualMachine> VMArrayList = new ArrayList<VirtualMachine>();


            Collection hostCollection = topology.getNetwork().getHosts();
            System.out.println(hostCollection.toString());
            System.out.println(hostCollection.size());
            Object[] hostsArray = hostCollection.toArray();

            int vmsPerHost = numOfVmsAsInteger / hostsArray.length;
            System.out.println(vmsPerHost);
            int vmCount = 0;
            // Creating VMS
            for (int m = 0; m < hostsArray.length; m++) {
                for (int a = 0; a < vmsPerHost; a++) {
                    VirtualMachine vm = tenantManagerSystem.createVirtualMachine(firstTenant, Integer.toString(vmCount), (Host) hostsArray[m]);
                    VMArrayList.add(vm);
                    vmCount++;
                }
                System.out.println(vmCount);
                System.out.println(vmsPerHost * hostsArray.length);

            }
            int vmCount2 = vmCount;
            for (int m = 0; m < numOfVmsAsInteger - vmCount2; m++) {
                VirtualMachine vm = tenantManagerSystem.createVirtualMachine(firstTenant, Integer.toString(vmCount));
                VMArrayList.add(vm);
                vmCount++;

            }
            System.out.println(vmCount);
            System.out.println(VMArrayList.size());


            ArrayList<Flow> AllUpdatedFlows = new ArrayList<Flow>();
            AllUpdatedPaths = new ArrayList<Path>();
            PathMapper pathMapper = new PathMapper(controller);


            String line;
            while ((line = input.readLine()) != null && line.length() > 10) {    //this condition to break out of the loop when "stop" is recieved
                System.out.println("Flow " + i + "  = " + line);

                String[] flowDetails = line.split(" ");
                ArrayList<String> flowDetailsAL = new ArrayList<String>();

                for (int k = 0; k < flowDetails.length; k++) {
                    flowDetailsAL.add(flowDetails[k]);
                }

                AllFlows.add(flowDetailsAL);
                i++;
                VirtualMachine temp1 = null;
                VirtualMachine temp2 = null;
                for (int p = 0; p < VMArrayList.size(); p++) {
                    if (flowDetailsAL.get(1).equals(VMArrayList.get(p).getName())) {
                        temp1 = VMArrayList.get(p);

                    }
                    if (flowDetailsAL.get(2).equals(VMArrayList.get(p).getName())) {
                        temp2 = VMArrayList.get(p);
                    }

                }
                System.out.println(temp1);
                System.out.println(temp2);
                System.out.println(flowDetailsAL);


                long rate = (long) (Double.parseDouble(flowDetailsAL.get(8)) * 1000);
                long burst = (long) (Double.parseDouble(flowDetailsAL.get(9)) * 1000 / 8);
                Double deadline = Double.parseDouble(flowDetailsAL.get(10)) * 1000;
                System.out.println(rate + "  " + burst + "  " + deadline);
                System.out.println(VMArrayList.size());
                System.out.println(VMArrayList.get(VMArrayList.size() - 1));
                Flow createdFlow = tenantManagerSystem.createFlow(flowDetailsAL.get(0), temp1, temp2, InetAddress.getByName(flowDetailsAL.get(3)), InetAddress.getByName(flowDetailsAL.get(4)), Integer.parseInt(flowDetailsAL.get(5)), Integer.parseInt(flowDetailsAL.get(6)), Integer.parseInt(flowDetailsAL.get(7)), rate, burst, deadline);
                AllUpdatedFlows.add(createdFlow);
                System.out.println("hhhhhhhhhhhhhhhhhh" + AllUpdatedFlows.size());
                System.out.println(createdFlow.toString());


                AllUpdatedPaths.clear();
                int fd ;
                for (fd = 0; fd < AllUpdatedFlows.size(); fd++) {
                    Path path = pathMapper.get(AllUpdatedFlows.get(fd).getEntity());
                    AllUpdatedPaths.add(path);

                }

                Path toBeSent = AllUpdatedPaths.get(AllUpdatedPaths.size() - 1);


                if (toBeSent != null)
                    toServer.println(toBeSent.toString());
                else
                    toServer.println("no path");

                if (fd == VMArrayList.size())
                    break;

            }

            System.out.println("breaked khalas");
            System.out.println(AllUpdatedPaths);
            System.out.println(AllFlows);
            int f ;
            for ( f = 0; f < AllUpdatedPaths.size() ;f ++){
                if (AllUpdatedPaths.get(f) != null)
                    toServer.println(AllUpdatedPaths.get(f).toString());
                else
                    toServer.println("no path");
            }





        }

        catch (TenantManagerException e) {
//            System.out.println(input.readLine());
            toServer.println("establish");
            for (int fg = 0; fg < AllUpdatedPaths.size(); fg++) {
                if (AllUpdatedPaths.get(fg) != null)
                    toServer.println(AllUpdatedPaths.get(fg).toString());
                else
                    toServer.println("no path");
            }
        }

        catch (Exception e) {
            e.printStackTrace();

        }


        if (withGui) {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

}