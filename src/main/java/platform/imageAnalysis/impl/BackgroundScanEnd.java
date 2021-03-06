package platform.imageAnalysis.impl;

import org.bytedeco.javacpp.opencv_core;
import platform.imageAnalysis.AnalysisResult;
import platform.imageAnalysis.ImageProcessor;
import platform.imageAnalysis.impl.components.ImageCompare;
import platform.imageAnalysis.impl.outputObjects.BackgroundScanEndResult;
import platform.behaviors.components.InMemoryBackground;

import java.awt.image.BufferedImage;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static org.bytedeco.javacpp.opencv_imgproc.cvtColor;
import static org.bytedeco.javacv.Java2DFrameUtils.toBufferedImage;
import static org.bytedeco.javacv.Java2DFrameUtils.toMat;

public class BackgroundScanEnd extends ImageProcessor {

    Map<String, BufferedImage> bufferedImageMap;
    Map<String, Integer> counterMap;
    Map<String, Integer> lowPassCounterMap;

    @Override
    public void init() {
        bufferedImageMap = new HashMap<>();
        counterMap = new HashMap<>();
        lowPassCounterMap = new HashMap<>();
    }

    public AnalysisResult performProcessing(String cameraId, BufferedImage bufferedImage, Map<String, Object> additionalIntAttr){

        opencv_core.Mat input =  toMat(bufferedImage); boolean backgroundScanFinished = false;

        /*opencv_core.Mat output = inputImage.clone();
        cvtColor(inputImage, output, CV_BGR2GRAY);*/

        BufferedImage bufferedImage2 = toBufferedImage(input);

        if (additionalIntAttr.containsKey("inMemBackground")){
            InMemoryBackground inMemoryBackground = (InMemoryBackground) additionalIntAttr.get("inMemBackground");
            if(inMemoryBackground != null) {
                opencv_core.Mat neCornerImage = inMemoryBackground.getNEImage(cameraId); // need to convert to BW
                if (neCornerImage != null) {

                    BufferedImage tempBI = toBufferedImage(neCornerImage);
                    //compare current to NE corner
                    ImageCompare imageCompare2 = new ImageCompare(bufferedImage2,tempBI);
                    imageCompare2.setParameters(12, 7, 5, 10);
                    /*float similarity2 = imageCompare2.compare();

                    if(similarity2 > 0.98){
                        //spazz out
                        System.out.println("WAGHAHHA");
                        backgroundScanFinished = true;

                    }*/
                }
            }
        }

        Map<String,Serializable> out = new HashMap<>();
        BackgroundScanEndResult backgroundScanEndResult = new BackgroundScanEndResult(backgroundScanFinished);
        out.put("backgroundScanEndResult", backgroundScanEndResult);

        AnalysisResult analysisResult = new AnalysisResult(input,out);

        return analysisResult;
    }

}
