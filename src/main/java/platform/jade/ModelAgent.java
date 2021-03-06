package platform.jade;

import jade.core.AID;
import jade.core.ServiceException;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.core.messaging.TopicManagementHelper;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import platform.MultiCameraCore_View;
import platform.agents.ModelAndMCA;
import platform.MultiCameraCore;
import platform.camera.Camera;
import platform.camera.impl.SimulatedCamera;
import platform.goals.MultiCameraGoal;
import platform.jade.utilities.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


public class ModelAgent extends ControlledAgentImpl implements ModelAndMCA {

    private final static Logger LOGGER = Logger.getLogger(ModelAgent.class.getName());

    MultiCameraCore mcp_application = null;

    String viewAgentName;
    String dataFusionAgentName;

    ///////////////////////////////////////////////////////////////////////////
    /////                       AGENCIES                                    ///
    ///////////////////////////////////////////////////////////////////////////

    protected void setup() {

        LOGGER.setLevel(Level.CONFIG);

        LOGGER.config("ModelAgent created, beginning setup.");

        LOGGER.config("ModelAgent " + getAID().getName() + " initializing.");


        Object[] args = getArguments();

        if (args != null && args.length > 1) {

            viewAgentName = (String) args[1];
            mcp_application = MultiCameraCore.build( args);
            mcp_application.setup(this);

        }

        if (mcp_application != null && mcp_application.getCameraManager() != null) {
            LOGGER.config("ModelAgent " + getAID().getName() + " initialized.");
        } else {
            LOGGER.severe("ModelAgent failed to start due incorrect arguments.");
            doDelete();
        }

    }

    @Override
    public void addSnapshotListener() {

        LOGGER.config("Model agent snapshot listener behavior added.");

        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(201);
                ACLMessage msg = myAgent.receive(mt);
                if (msg != null) {
                    try {
                        Object o = msg.getContentObject();

                        if (o instanceof SnapshotConfirmationMessage) {
                            SnapshotConfirmationMessage snapshotConfirmationMessage = (SnapshotConfirmationMessage) o;

                            Camera camera = mcp_application.getCameraManager().getCameraByID(snapshotConfirmationMessage.getCameraID());
                            camera.getAdditionalAttributes().put("snapTaken", snapshotConfirmationMessage.getSnapShotName());

                        }

                    } catch (UnreadableException e) {
                        e.printStackTrace();
                    }
                }
                block();
            }
        });

    }

    @Override
    public void addAnalysisResultListeners() {

        LOGGER.config("Model agent analysis results listeners behavior added.");

        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                ACLMessage msg = myAgent.receive(mt);
                if (msg != null) {
                    try {
                        Object content = msg.getContentObject();

                        if (content instanceof CombinedAnalysisResultsMessage) {
                            CombinedAnalysisResultsMessage analysisResultsMessage = (CombinedAnalysisResultsMessage) content;

                            /*for each goals results*/
                            for (String key : analysisResultsMessage.getCombinedResultMap().keySet()) {

                                mcp_application.getGoalById(key).getNewAnalysisResultsMap().putAll(analysisResultsMessage.getCombinedResultMap().get(key));

                            }
                        } else if (false) {

                        }

                    } catch (UnreadableException e) {
                        e.printStackTrace();
                    }
                }
                block();
            }
        });

    }

    @Override
    public void addCameraStreamCyclicUpdate(Camera camera) {

        LOGGER.config("Model agent analysis updater behavior added.");

        ModelAgent m = this;

        addBehaviour(new TickerBehaviour(this, 1000) {
            protected void onTick() {

                mcp_application.createCameraStreamAnalysisUpdateMessage(m, camera);

            }
        });

    }

    @Override
    public void sendCameraAnalysisUpdate(Camera camera, CameraAnalysisMessage cameraAnalysisMessage) {

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        try {
            msg.setContentObject(cameraAnalysisMessage);
        } catch (IOException e) {
            e.printStackTrace();
        }
        msg.addReceiver(new AID("CameraAnalyser" + camera.getIdAsString(), AID.ISLOCALNAME));
        send(msg);

    }

    @Override
    public boolean addDataFusionAgent() {

        String name = "DataFuser";

        Object[] args = new Object[2];
        args[0] = getAID().getName();
        args[1] = viewAgentName;

        AgentContainer c = getContainerController();

        try {
            AgentController a = c.createNewAgent(name, "platform.jade.DataFusionAgent", args);
            a.start();

            dataFusionAgentName = a.getName();

            LOGGER.config("Model agent created DataFuserAgent, " + dataFusionAgentName);

            return true;
        } catch (Exception e) {

            LOGGER.severe("Model agent failed to create DataFusionAgent");

            return false;
        }


    }

    public void addCameraMonitorListeners(MultiCameraCore multiCameraCore, List<Camera> cameraList) {


        multiCameraCore.getHeartbeat().init(cameraList);

        for (Camera camera : cameraList) {

            TopicManagementHelper topicHelper = null;

            try {

                topicHelper = (TopicManagementHelper) getHelper(TopicManagementHelper.SERVICE_NAME);
                final AID topic = topicHelper.createTopic("CameraMonitor" + camera.getIdAsString());
                topicHelper.register(topic);

                addBehaviour(new CyclicBehaviour(this) {

                    CameraHeartbeatMessage cameraHeartbeatMessage;

                    public void action() {
                        ACLMessage msg = myAgent.receive(MessageTemplate.MatchTopic(topic));
                        if (msg != null) {
                            try {
                                Object content = msg.getContentObject();
                                if (content instanceof CameraHeartbeatMessage) {
                                    cameraHeartbeatMessage = (CameraHeartbeatMessage) content;

                                    System.out.println(System.nanoTime() - cameraHeartbeatMessage.getTimeCreated());
                                    System.out.println(cameraHeartbeatMessage.buildMessage());
                                    //if a camera monitor message says a camera is not working..
                                    if (cameraHeartbeatMessage.isWorking() == false) {
                                        Camera camera2 = mcp_application.getCameraManager().getCameraByID(cameraHeartbeatMessage.getId());

                                        LOGGER.info(camera2.getId() + " is not working.");
                                        camera2.setWorking(false);

                                        camera2.getCurrentGoals().clear();
                                        camera2.setViewControllingGoal(null);

                                        for (MultiCameraGoal m : camera2.getMultiCameraGoalList()) {
                                            m.getNewAnalysisResultsMap().remove(camera2.getIdAsString());
                                            m.getProcessedInfoMap().remove(camera2);
                                        }

                                    }
                                    //if a message says it is working..
                                    else {
                                        //and if the application thought is was not working..
                                        Camera camera1 = mcp_application.getCameraManager().getCameraByID(cameraHeartbeatMessage.getId());
                                        if (!camera1.isWorking()) {
                                            camera1.getCameraState().setReconnectable(true);
                                        }
                                    }
                                    mcp_application.getHeartbeat().recordHeartbeat(cameraHeartbeatMessage.getId());
                                    LOGGER.info("- " +
                                            myAgent.getLocalName() + " <- " +
                                            cameraHeartbeatMessage.getId() + " " + cameraHeartbeatMessage.isWorking());
                                }
                            } catch (UnreadableException e) {
                                e.printStackTrace();
                            }
                        }

                        mcp_application.getCameraManager().reinitNotWorkingCameras(mcp_application);

                        block();
                    }
                });


            } catch (ServiceException e) {
                e.printStackTrace();
            }

        }

        addBehaviour(new TickerBehaviour(this, multiCameraCore.getHeartbeat().getTimer()) {

            @Override
            protected void onTick() {

                List<String> failedHeartbeatIDs = multiCameraCore.getHeartbeat().checkHeartBeats();

                for (String s : failedHeartbeatIDs) {
                    multiCameraCore.getCameraManager().getCameraByID(s).setWorking(false);
                }

            }

        });


        LOGGER.config("Model agent added camera monitor listeners.");

    }

    @Override
    public void addMCAExecutionLoop() {

        addBehaviour(new TickerBehaviour(this, 25) {
            protected void onTick() {

                List<CommunicationAction> actionMessages = mcp_application.executeMAPELoop();

                for (CommunicationAction c: actionMessages){

                    ACLMessage msg = new ACLMessage(201);
                    try {
                        msg.setContentObject(c);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    msg.addReceiver(new AID(c.getId(), AID.ISLOCALNAME));
                    send(msg);

                }

            }
        } );

        addBehaviour(new TickerBehaviour(this, 1000) {
            protected void onTick() {

                mcp_application.goalUpdate();

            }
        } );

/*        addBehaviour(new TickerBehaviour(this, 100) {
            protected void onTick() {

                for (Camera camera: mcp_application.getWorkingCameras()){
                    if (!(camera instanceof SimulatedCamera)) {
                        if (camera.getViewCapabilities().isPTZ()) {
                            if (camera.getViewControllingGoal() != null) {
                                camera.getViewControllingGoal().executeCameraMotionAction(camera);
                                camera.getViewControllingGoal().cameraMotionAccurateController(camera);
                            }
                        }
                    }
                }

            }
        } );*/






        LOGGER.config("Model agent added core execution loop.");

    }

    @Override
    public void addCameraStreamAnalyzer(MultiCameraCore mcp_application, Camera camera) {

        String cameraType;
        if (camera instanceof SimulatedCamera){
            cameraType = "SIM";
        }
        else { cameraType = "NONSIM";}

        String name = "CameraAnalyser" + camera.getIdAsString();

        Object[] args = new Object[9];
        args[0] = camera.getStreamURI();
        args[1] = camera.getUsername();
        args[2] = camera.getPassword();
        args[3] = String.valueOf(camera.isWorking());
        args[4] = cameraType;
        args[5] = camera.getIdAsString();
        args[6] = getAID().getName();

        if (mcp_application.getAdditionalFields().containsKey("testMode")) {
            args[7] = "testMode";
        }
        else {
            args[7] = "normalMode";
        }

        args[8] = dataFusionAgentName;

        AgentContainer c = getContainerController();

        try {
            AgentController a = c.createNewAgent( name, "platform.jade.AnalysisAgent", args );
            a.start();

            LOGGER.config("Model agent created camera Analyzer, " + a.getName());
        }
        catch (Exception e){

            LOGGER.severe("Model agent failed to add camera monitor listener.");
        }


    }

    @Override
    public void addViewCyclicCommunicationBehavior() {

        addBehaviour(new TickerBehaviour(this, 1000) {
            protected void onTick() {

                if (mcp_application != null) {

                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    try {
                        msg.setContentObject(new MultiCameraCore_View(mcp_application));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    msg.addReceiver(new AID(viewAgentName, AID.ISGUID));
                    send(msg);

                }
            }
        } );

    }

    @Override
    public Object[] getArgs() {
        return getArguments();
    }

    @Override
    public void cancelInit() {
        doDelete();
    }

    @Override
    public void addCameraMonitor(MultiCameraCore mcp_application, Camera camera) {

        String name = "CameraMonitor" + camera.getIdAsString();

        Object[] args = new Object[4];
        args[0] = camera.getFilename();

        int heartbeat = Integer.valueOf((String)mcp_application.getAdditionalFields().get("heartbeat"));

        if (heartbeat>0){
            args[1] = heartbeat;
        }
        else{
            args[1] = 30000;
        }

        args[2] = getAID().getName();

        AgentContainer c = getContainerController();

        try {
            AgentController a = c.createNewAgent( name, "platform.jade.CameraMonitorAgent", args );
            a.start();

            LOGGER.config("Model agent created camera monitor, " + a.getName());

        }
        catch (Exception e){

            LOGGER.severe("Model agent failed to create camera monitor");
        }

    }

}