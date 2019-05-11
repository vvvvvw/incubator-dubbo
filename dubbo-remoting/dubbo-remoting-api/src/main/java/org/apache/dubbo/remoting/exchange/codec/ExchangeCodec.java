/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.exchange.codec;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.io.StreamUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.buffer.ChannelBufferInputStream;
import org.apache.dubbo.remoting.buffer.ChannelBufferOutputStream;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.telnet.codec.TelnetCodec;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.IOException;
import java.io.InputStream;

/**
 * ExchangeCodec.
 */
////该类继承了TelnetCodec，是信息交换编解码器。
    //dubbo将一条消息分成了协议头和协议体，用来解决粘包拆包问题，但是头跟体在编解码上有区别
public class ExchangeCodec extends TelnetCodec {

    //协议头长度：16字节 = 128Bits
    // header length.
    protected static final int HEADER_LENGTH = 16;
    //MAGIC二进制：1101101010111011，十进制：55995
    // magic header.
    protected static final short MAGIC = (short) 0xdabb;
    //Magic High，也就是0-7位：11011010
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    //Magic Low  8-15位 ：10111011
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    //128 二进制：10000000
    // message flag.
    protected static final byte FLAG_REQUEST = (byte) 0x80;
    //64 二进制：1000000
    protected static final byte FLAG_TWOWAY = (byte) 0x40;
    //32 二进制：100000
    protected static final byte FLAG_EVENT = (byte) 0x20;
    //31 二进制：11111
    protected static final int SERIALIZATION_MASK = 0x1f;
    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }

    //根据消息的类型来分别进行编码，分为三种情况：Request类型、Response类型以及其他(父类( Telnet ) 处理)
    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        if (msg instanceof Request) {
            //// 如果消息是Request类型，对请求消息编码
            encodeRequest(channel, buffer, (Request) msg);
        } else if (msg instanceof Response) {
            // 如果消息是Response类型，对响应消息编码
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            // 直接让父类( Telnet ) 处理，目前是 Telnet 命令的结果。
            super.encode(channel, buffer, msg);
        }
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        // FIXME: 有可能多线程从一个channel中并发读取么？  by 15258 2019/5/6 12:59
        int readable = buffer.readableBytes();
        // 读取前16字节的协议头数据，如果数据不满16字节，则读取全部
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        buffer.readBytes(header);
        // 解码
        return decode(channel, buffer, readable, header);
    }

    //解码前的一些核对过程，包括检测是否为dubbo协议，是否有拆包现象等
    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        // 核对魔数（该数字固定）
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {
            // todo 不为魔数 有可能是因为流出错，导致有一部分字节被读出，导致后面不是一个完整消息 by 15258 2019/5/6 12:54
            //如果 header[0]和header[1] 不为魔数
            int length = header.length;
            if (header.length < readable) {
                //如果读取的时候readable的数量已经超过16个
                // 将 buffer中的数据 完全复制到 `header` 数组中(readable个字节)
                header = Bytes.copyOf(header, readable);
                buffer.readBytes(header, length, readable - length);
            }
            //此时header中的数据包括了 在进入decode方法时的所有可读数据
            for (int i = 1; i < header.length - 1; i++) {
                //从前往后遍历，直到发现 魔数匹配 消息开头
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    // FIXME: buffer什么时候会把已经被读取的消息丢弃，不丢弃的话想重置就重置？  by 15258 2019/5/6 13:44
                    // 重置readerindex
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            // FIXME: 发现了消息开头以后为什么不直接执行下去，要调用super.decode  by 15258 2019/5/6 13:46
            return super.decode(channel, buffer, readable, header);
        }
        //如果 header[0] 和 header[1] 为魔数
        // Header 长度不够，返回需要更多的输入，解决拆包现象
        // check length.
        if (readable < HEADER_LENGTH) {
            // FIXME: 如何聚合  by 15258 2019/5/6 12:41
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length.
        int len = Bytes.bytes2int(header, 12);
        // 检查信息头长度
        checkPayload(channel, len);

        int tt = len + HEADER_LENGTH;
        // 总长度不够，返回需要更多的输入，解决拆包现象
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            // 对body反序列化
            return decodeBody(channel, is, header);
        } finally {
            // 如果不可用
            if (is.available() > 0) {
                try {
                    // 打印错误日志
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    // 跳过未读完的流
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    //解码的过程，并且对协议头和协议体分开解码，协议头编码是做或运算，而解码则是做并运算，协议体用反序列化的方式解码，同样也是分为了Request类型、Response类型进行解码。
    // 对body反序列化
    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        // 用并运算符
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // 获得请求id
        // get request id.
        long id = Bytes.bytes2long(header, 4);
        // 如果第16位为0，则说明是响应
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            // 如果第18位不是0，则说明是心跳事件
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(true);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                // 如果响应是成功的
                if (status == Response.OK) {
                    Object data;
                    if (res.isHeartbeat()) {
                        // 如果是心跳事件，则心跳事件的解码
                        data = decodeHeartbeatData(channel, in);
                    } else if (res.isEvent()) {
                        // 如果是事件，则事件的解码
                        data = decodeEventData(channel, in);
                    } else {
                        // 否则执行普通解码
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    // 重新设置响应结果
                    res.setResult(data);
                } else {
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            // 对请求类型解码
            Request req = new Request(id);
            // 设置版本号
            req.setVersion(Version.getProtocolVersion());
            // 如果第17位不为0，则是双向
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            // 如果18位不为0，则是心跳事件
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(true);
            }
            try {
                // 反序列化
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                Object data;
                if (req.isHeartbeat()) {
                    // 如果请求是心跳事件，则心跳事件解码
                    data = decodeHeartbeatData(channel, in);
                } else if (req.isEvent()) {
                    // 如果是事件，则事件解码
                    data = decodeEventData(channel, in);
                } else {
                    // 否则，用普通解码
                    data = decodeRequestData(channel, in);
                }
                // 把重新设置请求数据
                req.setData(data);
            } catch (Throwable t) {
                // 设置是异常请求
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null) {
            return null;
        }
        Request req = future.getRequest();
        if (req == null) {
            return null;
        }
        return req.getData();
    }

    //对Request类型的消息进行编码
    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        Serialization serialization = getSerialization(channel);
        // 创建16字节的字节数组
        // header.
        byte[] header = new byte[HEADER_LENGTH];
        //// 设置前16位数据，也就是设置header[0]和header[1]的数据为Magic High和Magic Low
        // set magic number.
        Bytes.short2bytes(MAGIC, header);

        //// 16-23位为serialization编号，用到或运算10000000|serialization编号，例如serialization编号为11111，则为00011111
        // 0x80 第一个1表示请求
        // set request and serialization flag.
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        if (req.isTwoWay()) {
            //// 继续上面的例子，00011111|1000000 = 01011111
            header[2] |= FLAG_TWOWAY;
        }
        if (req.isEvent()) {
            // 继续上面的例子，01011111|100000 = 011 11111 可以看到011代表请求标记、双向、是事件，这样就设置了16、17、18位，后面19-23位是Serialization 编号
            header[2] |= FLAG_EVENT;
        }

        // 设置32-95位请求id
        // set request id.
        Bytes.long2bytes(req.getId(), header, 4);

        // // 编码 `Request.data` 到 Body ，并写入到 Buffer
        // encode request data.
        int savedWriteIndex = buffer.writerIndex();
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
        // 对body数据序列化
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
        if (req.isEvent()) {
            // 如果该请求是事件// 特殊事件编码，直接写data
            encodeEventData(channel, out, req.getData());
        } else {
            // 正常请求编码
            encodeRequestData(channel, out, req.getData(), req.getVersion());
        }
        // 释放资源
        out.flushBuffer();
        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        bos.flush();
        bos.close();
        //获取data长度
        int len = bos.writtenBytes();
        //检验消息长度'是否过长
        checkPayload(channel, len);
        // 设置96-127位：Body值
        Bytes.int2bytes(len, header, 12);

        // 把header写入到buffer
        // write
        buffer.writerIndex(savedWriteIndex);
        buffer.writeBytes(header); // write header.
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        int savedWriteIndex = buffer.writerIndex();
        try {
            Serialization serialization = getSerialization(channel);
            // header.
            // 创建16字节的字节数组
            byte[] header = new byte[HEADER_LENGTH];
            // set magic number.
            // 设置前16位数据，也就是设置header[0]和header[1]的数据为Magic High和Magic Low
            Bytes.short2bytes(MAGIC, header);
            // set request and serialization flag.
            // 继续上面的例子，00011111|1000000 = 01011111
            header[2] = serialization.getContentTypeId();
            // 继续上面的例子，01011111|100000 = 011 11111 可以看到011代表请求标记、双向、是事件，这样就设置了16、17、18位，后面19-23位是Serialization 编号
            if (res.isHeartbeat()) {
                header[2] |= FLAG_EVENT;
            }
            // set response status.
            // 设置24-31位为状态码
            byte status = res.getStatus();
            header[3] = status;
            // set request id.
            // 设置32-95位为请求id
            Bytes.long2bytes(res.getId(), header, 4);

            // 写入数据
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            // 对body进行序列化
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            // encode response data or error message.
            if (status == Response.OK) {
                if (res.isHeartbeat()) {
                    // 对心跳事件编码
                    encodeHeartbeatData(channel, out, res.getResult());
                } else {
                    //// 对普通响应编码
                    encodeResponseData(channel, out, res.getResult(), res.getVersion());
                }
            } else {
                out.writeUTF(res.getErrorMessage());
            }
            // 释放
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            int len = bos.writtenBytes();
            checkPayload(channel, len);
            Bytes.int2bytes(len, header, 12);
            // write
            buffer.writerIndex(savedWriteIndex);
            buffer.writeBytes(header); // write header.
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer
            buffer.writerIndex(savedWriteIndex);
            //如果在写入数据失败，则返回响应格式错误的返回码
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        // 发送响应
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // FIXME: 这些异常是业务处理抛出的，还是dubbo框架抛出的？如果是业务处理抛出的，那不是应该封装到response中么？  by 15258 2019/5/6 10:07
            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    @Deprecated
    protected Object decodeHeartbeatData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }


}
