package com.example.fixture

import java.net.Socket

/**
 * Contains a call the operation-dispatch lane instruments (`Socket.connect`), so the
 * post-transform bytecode of EACH build type can be inspected for injected Bugsee calls.
 */
object GuardedCalls {
    fun connect(socket: Socket, address: java.net.SocketAddress) {
        socket.connect(address)
    }
}
