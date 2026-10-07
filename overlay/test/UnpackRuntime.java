package sts1solver;
import java.nio.file.Paths;
/** CI uses production extraction, including its executable permission restoration. */
public class UnpackRuntime {
    public static void main(String[] args) throws Exception {
        System.out.println(BackendRuntime.unpack(Paths.get(args[0]), Paths.get(args[1])));
    }
}
