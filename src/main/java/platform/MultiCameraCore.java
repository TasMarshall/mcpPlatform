package platform;

import platform.agents.ModelAndMCA;
import platform.camera.Camera;
import platform.camera.impl.SimulatedCamera;
import platform.cameraManager.CameraManager;
import platform.goals.MultiCameraGoal;
import platform.imageAnalysis.AnalysisTypeManager;
import platform.jade.ModelAgent;
import platform.map.GlobalMap;
import platform.behaviors.AdaptationTypeManager;
import platform.behaviors.GoalMAPEBehavior;
import platform.jade.utilities.CameraAnalysisMessage;
import platform.jade.utilities.CommunicationAction;
import platform.utilities.Heartbeat;
import uk.co.caprica.vlcj.discovery.NativeDiscovery;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static archive.MapView.distanceInLatLong;


public class MultiCameraCore {

    private final static Logger LOGGER = Logger.getLogger(MultiCameraCore.class.getName());

    MultiCameraCore mcp_application;

    /** A single object which managers cameras of varying types*/
    private CameraManager cameraManager;

    /** A list of multi camera application goals */
    private  List<MultiCameraGoal> multiCameraGoals;

    /** A library of visual analysis algorithms.*/
    private AnalysisTypeManager analysisTypeManager;

    /** A library of behavioral algorithms.*/
    private AdaptationTypeManager adaptationTypeManager;

    /** AdditionalFields - allows for additional fields to be added including customization values
     * for internal components and configuration information which can be used by behaviors.*/
    private Map<String, Object> additionalFields;


    ////////////////////////////////////////////////////////////////////////
    /////                       INTERNAL                               /////
    ////////////////////////////////////////////////////////////////////////

    private GlobalMap globalMap;
    private List<CommunicationAction> agentActions = new ArrayList<>();

    private boolean cameraMonitorsAdded;
    private boolean cameraStreamAnalyzersAdded;
    private boolean coreBehaviorsAdded;
    private boolean dataFusionAgentAdded;

    private Heartbeat heartbeat;

    ///////////////////////////////////////////////////////////////////////////
    /////                       CONSTRUCTOR                               /////
    ///////////////////////////////////////////////////////////////////////////

    public MultiCameraCore(List<MultiCameraGoal> multiCameraGoals, List<Camera> cameras, AnalysisTypeManager analysisTypeManager, AdaptationTypeManager adaptationTypeManager, Map<String,Object> additionalFields) {

        LOGGER.setLevel(Level.CONFIG);

        LOGGER.config("MultiCameraCore created, beginning setup.");

        this.multiCameraGoals = multiCameraGoals;
        this.cameraManager = new CameraManager(cameras);
        this.analysisTypeManager = analysisTypeManager;
        this.adaptationTypeManager = adaptationTypeManager;

        if(additionalFields != null) {
            this.additionalFields = additionalFields;
        }
        else {
            this.additionalFields =  new HashMap<>();
        }

        init();
    }

    public MultiCameraCore() {
        mcp_application = this;
    }

    /** This function sets up an mcp application from a model m.*/
    public MultiCameraCore setup(ModelAndMCA m, Object[] args) {

        MultiCameraCore.initDependencyObjects();

        if(!(args[0]).equals("java")) {
            mcp_application.buildMCAFromConfigFile(args);
        }
        else {
            mcp_application.buildMCAFromJavaClass();
        }

        if (mcp_application != null && mcp_application.getCameraManager() != null) {
            mcp_application.buildComponentsAndBehaviors(m);
        }

        return mcp_application;

    }

    /** This function initializes dependency libraries etc.*/
    public static void initDependencyObjects() {

        //vlcj native library
        new NativeDiscovery().discover();

        //opencv
        //System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

    }

    /** This function processes arguments to build a MCA from a configuration file*/
    public void buildMCAFromConfigFile(Object[] args) {

        if (args != null && args.length > 0) {

            MultiCameraCore_Configuration mcp_application_configuration = new MultiCameraCore_Configuration();

            try {
                this.mcp_application = mcp_application_configuration.createMCAppFromMCPConfigurationFile((String) args[0] + ".xml");
            } catch (FileNotFoundException e) {
                LOGGER.severe("Specified multi-camera application configuration file not found.");
            }
        }

    }

    public void buildMCAFromJavaClass(){

        MCAJavaImpl mcaJava = new MCAJavaImpl();

        this.mcp_application = mcaJava.buildMCA();

    }

    /** This function initializes a build MCA object.*/
    public void init(){

        createGlobalMap(multiCameraGoals,getAllCameras());

        for(String s: analysisTypeManager.getStringToAnalysisMap().keySet()){
            analysisTypeManager.getImageProcessObject(s).init();
        }

        for (String s: adaptationTypeManager.getStringToAdaptivePolicyMap().keySet()){
            adaptationTypeManager.getAdaptivePolicy(s).init();
        }

        for (MultiCameraGoal multiCameraGoal: multiCameraGoals){
            multiCameraGoal.init(this,0.1, analysisTypeManager, adaptationTypeManager);
        }

    }

    ///////////////////////////////////////////////////////////////////////////
    /////                       MAPE LOOP                                 /////
    ///////////////////////////////////////////////////////////////////////////

    public List<CommunicationAction> executeMAPELoop() {

        agentActions = new ArrayList<>();

        monitor();
        analyse();
        plan();
        execute();

        removeNewInfo();

        return agentActions;

    }

    private void removeNewInfo() {
        for (MultiCameraGoal multiCameraGoal: getMultiCameraGoals()){
            for (Camera camera: multiCameraGoal.getCameras()) {
                multiCameraGoal.getNewAnalysisResultMap().remove(camera.getIdAsString());
            }
        }
    }

    public void monitor() {

        for (Camera camera: getWorkingCameras()){
            if (camera.getCameraState().connected == false || camera.getCameraState().initialized == false){
                //camera.init();
            }
            if (camera.getCameraState().calibrated == false){
                camera.setCalibrationGoals(this);
            }
            if (camera.getCameraState().initialized == true && camera.getCameraState().calibrated == true && camera.getCameraState().connected == true){
                camera.determineActiveGoals();
            }
        }

        List<CommunicationAction> communicationActions = new ArrayList<>();
        for (MultiCameraGoal multiCameraGoal: multiCameraGoals){
            //multiCameraGoal.recordResults();

            if (multiCameraGoal.isActivated()) {

                for (Camera camera : getWorkingCameras()) {
                    if (camera.getCurrentGoals().contains(multiCameraGoal)) {
                        communicationActions = multiCameraGoal.monitorBehaviours(camera);
                        if (communicationActions.size() != 0) agentActions.addAll(communicationActions);
                    }
                }

                for (GoalMAPEBehavior adaptivePolicy : multiCameraGoal.getGoalBehaviours()) {
                    CommunicationAction communicationAction = adaptivePolicy.monitor(multiCameraGoal);
                    if (communicationAction != null) agentActions.add(communicationAction);
                }

            }

        }

    }

    public void analyse() {

        List<CommunicationAction> communicationActions = new ArrayList<>();
        for (MultiCameraGoal multiCameraGoal: multiCameraGoals){

            if (multiCameraGoal.isActivated()) {

                for (Camera camera : getWorkingCameras()) {
                    communicationActions = multiCameraGoal.analysisBehaviours(camera);

                    if (communicationActions.size() != 0) agentActions.addAll(communicationActions);

                }

                for (GoalMAPEBehavior adaptivePolicy : multiCameraGoal.getGoalBehaviours()) {
                    CommunicationAction communicationAction = adaptivePolicy.analyse(multiCameraGoal);
                    if (communicationAction != null) agentActions.add(communicationAction);
                }

            }
        }

    }

    public void plan() {

        List<CommunicationAction> communicationActions = new ArrayList<>();
        for (MultiCameraGoal multiCameraGoal: multiCameraGoals){
            if (multiCameraGoal.isActivated()) {

                for (Camera camera : getWorkingCameras()) {
                    communicationActions = multiCameraGoal.planBehaviours(camera);
                    if (communicationActions.size() != 0) agentActions.addAll(communicationActions);

                }

                for (GoalMAPEBehavior adaptivePolicy : multiCameraGoal.getGoalBehaviours()) {
                    CommunicationAction communicationAction = adaptivePolicy.plan(multiCameraGoal);
                    if (communicationAction != null) agentActions.add(communicationAction);
                }
            }
        }
    }

    public void execute() {

        List<CommunicationAction> communicationActions;
        for (Camera camera: getWorkingCameras()){
            if (!(camera instanceof SimulatedCamera)) {
                if (camera.getViewCapabilities().isPTZ()) {
                    if (camera.getViewControllingGoal() != null) {
                        camera.getViewControllingGoal().executeCameraMotionAction(camera);
                    }
                }
            }
        }

        for (MultiCameraGoal multiCameraGoal: getMultiCameraGoals()){

            if (multiCameraGoal.isActivated()) {

                for (Camera camera: getWorkingCameras()) {
                    communicationActions = multiCameraGoal.executeBehaviours(camera);
                    if (communicationActions.size() != 0) agentActions.addAll(communicationActions);
                }

                for (GoalMAPEBehavior adaptivePolicy: multiCameraGoal.getGoalBehaviours()){
                    CommunicationAction communicationAction = adaptivePolicy.execute(multiCameraGoal);
                    if (communicationAction != null ) agentActions.add(communicationAction);
                }

            }

        }

    }

    ///////////////////////////////////////////////////////////////////////////
    /////                       CLASS FUNCTIONS                           /////
    ///////////////////////////////////////////////////////////////////////////

    /**This function adds a camera monitor for every camera in the system.*/
    protected boolean addCameraMonitors(ModelAndMCA m, List<Camera> cameraList){

        for (Camera c: cameraList) {
            m.addCameraMonitor(this, c);
        }

        return true;
    }

    protected boolean addCameraStreamAnalyzers(ModelAndMCA m, MultiCameraCore mcp_application) {

        for (Camera camera: mcp_application.getAllCameras()){

            m.addCameraStreamAnalyzer(mcp_application, camera);

        }

        return true;

    }

    public Heartbeat createHeartBeat(MultiCameraCore mcp_application) {

        int cameraMonitorTimer;

        if (mcp_application.getAdditionalFields().containsKey("heartbeat")
                && mcp_application.getAdditionalFields().get("heartbeat") instanceof String
                && Integer.valueOf((String)mcp_application.getAdditionalFields().get("heartbeat")) > 0
                && Integer.valueOf((String)mcp_application.getAdditionalFields().get("heartbeat")) < Integer.MAX_VALUE )
        {
            cameraMonitorTimer = Integer.valueOf((String)mcp_application.getAdditionalFields().get("heartbeat"));
        }
        else {
            cameraMonitorTimer = 60000;
        }

        return new Heartbeat(cameraMonitorTimer);

    }

    public void createCameraStreamAnalysisUpdateMessage(ModelAndMCA m, Camera camera) {

        CameraAnalysisMessage cameraAnalysisMessage = new CameraAnalysisMessage(camera.getCurrentGoals());

        if(camera.isWorking()) {
            m.sendCameraAnalysisUpdate(camera, cameraAnalysisMessage);
        }

    }

    public boolean addCoreBehaviours(ModelAndMCA m) {

        m.addMCAExecutionLoop();
        m.addControllerReceiver();
        m.addCameraMonitorListeners();
        addUpdateCameraAnalysers(this,m);
        m.addAnalysisResultListeners();
        m.addSnapshotListener();
        m.addViewCyclicCommunicationBehavior();

        return true;
    }

    public void addUpdateCameraAnalysers(MultiCameraCore mcp_application, ModelAndMCA m) {

        //SEND ACTIVE GOALS TO CAMERA_ANALYSER
        for (Camera camera: mcp_application.getAllCameras()) {

            m.addCameraStreamCyclicUpdate(camera);

        }
    }

    public void buildComponentsAndBehaviors(ModelAndMCA mca_agent) {

        dataFusionAgentAdded = addDataFusionAgent(mca_agent);
        cameraStreamAnalyzersAdded = addCameraStreamAnalyzers(mca_agent,this);
        heartbeat = createHeartBeat(this);   //new Heartbeat(cameraMonitorTimer);
        cameraMonitorsAdded = addCameraMonitors(mca_agent,this.getAllCameras());;

        coreBehaviorsAdded = addCoreBehaviours(mca_agent);

        mca_agent.addControllerReceiver();

    }

    private boolean addDataFusionAgent(ModelAndMCA mca_agent) {
        return  mca_agent.addDataFusionAgent();
    }

    public void createGlobalMap(List<MultiCameraGoal> multiCameraGoals, List<? extends Camera> cameras) {

        double minLat = Double.POSITIVE_INFINITY;
        double minLong = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double maxLong = Double.NEGATIVE_INFINITY;

        platform.map.Map.CoordinateSys coordinateSys = platform.map.Map.CoordinateSys.INDOOR;

        if (multiCameraGoals.size() > 0) {

            for (MultiCameraGoal multiCameraGoal : multiCameraGoals) {

                if (multiCameraGoal.getMap().getMapType() == platform.map.Map.MapType.LOCAL) {

                    if (multiCameraGoal.getMap().getLongMin() < minLong)
                        minLong = multiCameraGoal.getMap().getLongMin();
                    if (multiCameraGoal.getMap().getLongMax() > maxLong)
                        maxLong = multiCameraGoal.getMap().getLongMax();
                    if (multiCameraGoal.getMap().getLatMin() < minLat)
                        minLat = multiCameraGoal.getMap().getLatMin();
                    if (multiCameraGoal.getMap().getLatMax() > maxLat)
                        maxLat = multiCameraGoal.getMap().getLatMax();

                    if (multiCameraGoal.getMap().getCoordinateSys() == platform.map.Map.CoordinateSys.OUTDOOR) {
                        coordinateSys = platform.map.Map.CoordinateSys.OUTDOOR;
                    }

                }

            }

        }

        for (Camera camera: cameras){

            double camLat = camera.getLocation().getLatitude();
            double camLong = camera.getLocation().getLongitude();

            double dLat = distanceInLatLong((double)camera.getAdditionalAttributes().get("range"),camLat,camLong,0)[0];
            double dLong = distanceInLatLong((double)camera.getAdditionalAttributes().get("range"),camLat,camLong,90)[1];

            if (camLong - dLong < minLong)
                minLong = camera.getLocation().getLongitude()- dLong;
            if (camera.getLocation().getLongitude() + dLong > maxLong)
                maxLong = camera.getLocation().getLongitude() + dLong;
            if (camera.getLocation().getLatitude() - dLat < minLat)
                minLat = camera.getLocation().getLatitude() - dLat;
            if (camera.getLocation().getLatitude() +  dLat> maxLat)
                maxLat = camera.getLocation().getLatitude() + dLat;

        }

        if (minLat == Double.POSITIVE_INFINITY){
            minLat = Double.NEGATIVE_INFINITY;
        }
        if (minLong == Double.POSITIVE_INFINITY){
            minLong = Double.NEGATIVE_INFINITY;
        }
        if (maxLat == Double.NEGATIVE_INFINITY){
            maxLat = Double.POSITIVE_INFINITY;
        }
        if (maxLong == Double.NEGATIVE_INFINITY){
            maxLong = Double.POSITIVE_INFINITY;
        }

        globalMap = new GlobalMap(minLong - 0.0001, minLat- 0.0001, maxLong + 0.0001, maxLat + 0.0001);

    }

    public MultiCameraGoal getGoalById(String id){

        MultiCameraGoal output = null;

        for (MultiCameraGoal multiCameraGoal: multiCameraGoals){
            if (multiCameraGoal.getId().equals(id)){
                output = multiCameraGoal;
            }
        }

        return  output;

    }


    ///////////////////////////////////////////////////////////////////////////
    /////                       GETTERS AND SETTERS                       /////
    ///////////////////////////////////////////////////////////////////////////

    public List<Camera> getAllCameras(){
        return cameraManager.getCameras();
    }

    public List<Camera> getWorkingCameras(){
        return cameraManager.getWorkingCameras();
    }

    public GlobalMap getGlobalMap() {
        return globalMap;
    }

    public void setGlobalMap(GlobalMap globalMap) {
        this.globalMap = globalMap;
    }

    public CameraManager getCameraMonitor() {
        return cameraManager;
    }

    public void setCameraMonitor(CameraManager cameraMonitor) {
        this.cameraManager = cameraMonitor;
    }

    public void setAdditionalFields(Map<String, Object> additionalFields) {
        this.additionalFields = additionalFields;
    }

    public List<MultiCameraGoal> getMultiCameraGoals() {
        return multiCameraGoals;
    }

    public void setMultiCameraGoals(List<MultiCameraGoal> multiCameraGoals) {
        this.multiCameraGoals = multiCameraGoals;
    }

    public Map<String,Object> getAdditionalFields() {
        return additionalFields;
    }

    public CameraManager getCameraManager() {
        return cameraManager;
    }

    public void setCameraManager(CameraManager cameraManager) {
        this.cameraManager = cameraManager;
    }

    public AnalysisTypeManager getAnalysisTypeManager() {
        return analysisTypeManager;
    }

    public void setAnalysisTypeManager(AnalysisTypeManager analysisTypeManager) {
        this.analysisTypeManager = analysisTypeManager;
    }

    public AdaptationTypeManager getAdaptationTypeManager() {
        return adaptationTypeManager;
    }

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public boolean getCameraMonitorsAdded() {
        return cameraMonitorsAdded;
    }

}

