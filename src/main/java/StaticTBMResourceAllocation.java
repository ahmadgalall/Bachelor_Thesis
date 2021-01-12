import de.tum.ei.lkn.eces.core.Controller;
import de.tum.ei.lkn.eces.dnm.resourcemanagement.resourceallocation.ResourceAllocation;
import de.tum.ei.lkn.eces.dnm.resourcemanagement.resourceallocation.SelectResourceAllocation;
import de.tum.ei.lkn.eces.dnm.resourcemanagement.resourceallocation.TBM.TBMStaticDelaysAllocation;
import de.tum.ei.lkn.eces.network.Scheduler;

public class StaticTBMResourceAllocation implements SelectResourceAllocation {
    private final double hostDelay = 0.5/1000;
    private final double[] fourQueuesSwitchDelays = new double[]{0.1/1000, 1.0/1000, 6.0/1000, 24.0/1000};
    private final double[] eightQueuesSwitchDelays = new double[]{0.1/1000, 0.5/1000, 1.0/1000, 1.5/1000, 3.0/1000, 6.0/1000, 12.0/1000, 24.0/1000};


    public StaticTBMResourceAllocation() {
        super();
    }

    @Override
    public ResourceAllocation selectResourceAllocation(Controller controller, Scheduler scheduler) {
        if(scheduler.getQueues().length == 1)
            return new TBMStaticDelaysAllocation(controller, new double[]{hostDelay});
        else if(scheduler.getQueues().length == 4)
            return new TBMStaticDelaysAllocation(controller, fourQueuesSwitchDelays);
        else if(scheduler.getQueues().length == 8)
            return new TBMStaticDelaysAllocation(controller, eightQueuesSwitchDelays);
        else
            throw new RuntimeException("This number of queues ( " + scheduler.getQueues().length + " ) is not supported!");
    }
}
