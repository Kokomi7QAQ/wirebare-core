package top.sankokomi.wirebare.kernel.tcp

import kotlinx.coroutines.CoroutineScope
import top.sankokomi.wirebare.kernel.common.WireBareConfiguration
import top.sankokomi.wirebare.kernel.interceptor.tcp.TcpVirtualGateway
import top.sankokomi.wirebare.kernel.net.Port
import top.sankokomi.wirebare.kernel.net.TcpSessionStore
import top.sankokomi.wirebare.kernel.nio.NioCallback
import top.sankokomi.wirebare.kernel.proxy.NioProxyServer
import top.sankokomi.wirebare.kernel.service.WireBareProxyService
import top.sankokomi.wirebare.kernel.util.WireBareLogger
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * TCP 代理服务器
 *
 * 负责接收被代理客户端的请求，并通过 [TcpProxyTunnel]
 * 和 [TcpRealTunnel] 来与远程服务器进行通信并进行拦截处理，之后将请求结果返回给被代理客户端
 *
 * 请求过程如下（不包含拦截）：
 *
 * Real Client >> [TcpProxyServer] >>
 * [TcpProxyTunnel] >> [TcpRealTunnel] >>
 * Remote Server
 *
 * 响应过程如下（不包含拦截）：
 *
 * Remote Server >> [TcpRealTunnel] >>
 * [TcpProxyTunnel] >> [TcpProxyServer] >>
 * Real Server
 *
 * @see [TcpProxyTunnel]
 * @see [TcpRealTunnel]
 * */
internal class TcpProxyServer(
    private val sessionStore: TcpSessionStore,
    private val tcpVirtualGateway: TcpVirtualGateway,
    private val configuration: WireBareConfiguration,
    private val proxyService: WireBareProxyService
) : NioProxyServer(), NioCallback, CoroutineScope by proxyService {

    internal val proxyServerPort: Port

    override val selector: Selector = Selector.open()

    private val proxyServerSocketChannel = ServerSocketChannel.open().apply {
        configureBlocking(false)
        socket().bind(InetSocketAddress(0))
        register(selector, SelectionKey.OP_ACCEPT, this@TcpProxyServer)
        proxyServerPort = Port(socket().localPort.toShort())
    }

    override fun onAccept() {
        val proxySocketChannel = proxyServerSocketChannel.accept()
        val proxySocket = proxySocketChannel.socket()

        val session = sessionStore.query(
            // 这个端口号就是这次请求的来源端口号
            Port(proxySocket.port.toShort())
        ) ?: throw IllegalStateException("一个 TCP 请求因为找不到指定会话而代理失败")

        WireBareLogger.inetDebug(
            session,
            "代理服务器 $proxyServerPort 代理开始"
        )

        // 接收到被代理客户端的请求后开始代理
        val proxyTunnel = TcpProxyTunnel(
            proxySocketChannel,
            selector,
            proxyServerPort,
            session,
            tcpVirtualGateway,
            configuration
        )
        val realTunnel = TcpRealTunnel(
            SocketChannel.open(),
            selector,
            session,
            configuration,
            tcpVirtualGateway,
            proxyService
        )

        // 将 TcpProxyTunnel 与 TcpReadTunnel 关联并开始连接远程服务器
        proxyTunnel.attachRealChannel(realTunnel)
        realTunnel.attachProxyTunnel(proxyTunnel)
        realTunnel.connectRemoteServer()
    }

    override fun onConnected() {
    }

    override fun onRead() {
    }

    override fun onWrite(): Int {
        return -1
    }

    override fun onException(t: Throwable) {
    }

    override fun release() {
        proxyServerSocketChannel.close()
    }

}