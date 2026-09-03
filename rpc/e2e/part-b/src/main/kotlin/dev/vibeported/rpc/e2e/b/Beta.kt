package dev.vibeported.rpc.e2e.b

import dev.vibeported.rpc.e2e.a.Alpha

/** Only on nodes holding role `B`. A node without this jar cannot load any class that names it. */
object Beta {
    fun callB(): String = Alpha.callA() + "B"
}
