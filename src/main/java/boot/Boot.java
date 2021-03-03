package boot;

import connect.NioEndpoint;

import java.io.IOException;

public class Boot {
    public static void main(String[] args) throws IOException {
        NioEndpoint endpoint = new NioEndpoint();
        endpoint.start();
        while (true);
    }
}

