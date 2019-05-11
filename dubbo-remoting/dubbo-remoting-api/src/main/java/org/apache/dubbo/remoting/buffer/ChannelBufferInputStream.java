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

package org.apache.dubbo.remoting.buffer;

import java.io.IOException;
import java.io.InputStream;

//该类继承了InputStream,该类里面包装了读开始索引和结束索引，并且在构造方法中初始化这些属性。
public class ChannelBufferInputStream extends InputStream {

    /**
     * 缓冲区
     */
    private final ChannelBuffer buffer;
    /**
     * 记录开始读数据的索引
     */
    private final int startIndex;
    /**
     * 结束读数据的索引
     */
    private final int endIndex;

    public ChannelBufferInputStream(ChannelBuffer buffer) {
        this(buffer, buffer.readableBytes());
    }

    //lenght为buffer中还有多少数据未读
    public ChannelBufferInputStream(ChannelBuffer buffer, int length) {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length: " + length);
        }
        if (length > buffer.readableBytes()) {
            throw new IndexOutOfBoundsException();
        }

        this.buffer = buffer;
        // 记录开始读数据的索引
        startIndex = buffer.readerIndex();
        // 设置结束读数据的索引
        endIndex = startIndex + length;
        // 标记读索引
        buffer.markReaderIndex();
    }

    //返回读了多少数据。
    public int readBytes() {
        return buffer.readerIndex() - startIndex;
    }

    //返回还剩多少数据没读
    @Override
    public int available() throws IOException {
        return endIndex - buffer.readerIndex();
    }

    @Override
    public void mark(int readlimit) {
        buffer.markReaderIndex();
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    //读取一个字节的数据
    @Override
    public int read() throws IOException {
        if (!buffer.readable()) {
            return -1;
        }
        return buffer.readByte() & 0xff;
    }

    //读数据，返回读了数据长度。
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        // 判断是否还有数据可读
        int available = available();
        if (available == 0) {
            return -1;
        }

        // 获得需要读取的数据长度
        len = Math.min(available, len);
        buffer.readBytes(b, off, len);
        return len;
    }

    @Override
    public void reset() throws IOException {
        buffer.resetReaderIndex();
    }

    //跳过n长度，并返回跳过的数据
    @Override
    public long skip(long n) throws IOException {
        if (n > Integer.MAX_VALUE) {
            return skipBytes(Integer.MAX_VALUE);
        } else {
            return skipBytes((int) n);
        }
    }

    //返回跳过了多少数据
    private int skipBytes(int n) throws IOException {
        int nBytes = Math.min(available(), n);
        buffer.skipBytes(nBytes);
        return nBytes;
    }

}
