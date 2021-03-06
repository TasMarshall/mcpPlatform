package platform.imageAnalysis;

import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import platform.cameraManager.DirectStreamView;

import java.awt.image.BufferedImage;
import java.util.*;

import static org.bytedeco.javacpp.opencv_highgui.destroyWindow;
import static org.bytedeco.javacpp.opencv_highgui.imshow;
import static org.bytedeco.javacpp.opencv_highgui.namedWindow;
import static org.bytedeco.javacpp.opencv_imgcodecs.imwrite;
import static org.bytedeco.javacpp.opencv_imgproc.cvPutText;
import static org.bytedeco.javacv.Java2DFrameUtils.toMat;

public class ImageAnalyzer {

    String cameraType;
    String cameraId;

    DirectStreamView directStreamView;

    BufferedImage inputImage;

    AnalysisResult analysisResult;

    Set<ImageAnalysis> sortedAlgorithmSet;

    boolean develperMode =false;

    public CanvasFrame canvas;;
    final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();

    public ImageAnalyzer (DirectStreamView directStreamView, String cameraType, String cameraId, List<ImageAnalysis> imageAnalysisList, boolean developerMode){

        this.cameraType = cameraType;
        this.cameraId = cameraId;
        this.directStreamView = directStreamView;

        sortedAlgorithmSet = new TreeSet<>((o1, o2) -> {
            int comparePrecedence = ((ImageAnalysis) o2).getPrecedence();
            return ((ImageAnalysis) o1).getPrecedence() - comparePrecedence;
        });

        sortedAlgorithmSet.addAll(imageAnalysisList);

        this.develperMode = developerMode;
        // Request closing of the application when the image window is closed.
        if (!cameraType.equals("SIM")&&this.develperMode && sortedAlgorithmSet.size() > 0){
            canvas = new CanvasFrame("Analyzer Analysis Demo", 1.0);
            canvas.setCanvasSize(1280, 720);
        }

    }

    public void close() {
        if (develperMode) {
            canvas.dispose();
        }
    }

    public void performAnalysis(boolean cameraWorking, DirectStreamView directStreamView, Map<String, Object> storedAnalysisInformation) {

        if (this.directStreamView == null){
            this.directStreamView = directStreamView;
        }

        if (cameraWorking) {
            if (!(cameraType.equals("SIM"))) {
                if (this.directStreamView.isStreamIsPlaying() == true) {
                    inputImage = directStreamView.getBufferedImage();
                    analysisResult = new AnalysisResult(inputImage,new HashMap<>());

                    processImage(cameraId, storedAnalysisInformation);

                    if(develperMode && analysisResult.getOutput()!=null) {
                        canvas.showImage(converter.convert(analysisResult.getOutput()));
                    }

                }

            }
            else {

            }
        }



    }

    private void processImage(String cameraId, Map<String, Object> storedAnalysisInformation) {

        for (ImageAnalysis imageAnalysis: sortedAlgorithmSet){

            ImageProcessor imageProcessor = imageAnalysis.getImageProcessor();

            Map<String,Object> map =  imageAnalysis.getAdditionalIntAttr();
            map.putAll(storedAnalysisInformation);

            AnalysisResult analysisResult = imageProcessor.performProcessing(cameraId,inputImage,map);
            this.analysisResult.getAdditionalInformation().putAll(analysisResult.getAdditionalInformation());
            this.analysisResult.setOutput(analysisResult.getOutput());

        }

    }

    public AnalysisResult getAnalysisResult() {
        return analysisResult;
    }

    public void setAnalysisResult(AnalysisResult analysisResult) {
        this.analysisResult = analysisResult;
    }

}
