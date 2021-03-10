package connect;

import javafx.stage.Stage;

/**
 * 该类应该还有个辅助类RequestInfo。我这里省略了。
 */
public class Request {
    // 原是RequestInfo的字段
    private int stage;
    private Http11InputBuffer inputBuffer;


    public int getStage() {
        return stage;
    }

    public void setStage(int stage) {
        this.stage = stage;
    }

    public void setInputBuffer(Http11InputBuffer inputBuffer) {
        this.inputBuffer = inputBuffer;
    }

    public Http11InputBuffer getInputBuffer() {
        return inputBuffer;
    }
}
