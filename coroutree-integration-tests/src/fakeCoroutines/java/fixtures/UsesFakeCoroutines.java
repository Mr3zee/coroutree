package fixtures;

import kotlinx.coroutines.JobSupport;

public final class UsesFakeCoroutines {
    public static void main(String[] args) {
        new JobSupport().cancel(null);
        System.out.println("ran to the end");
    }
}
