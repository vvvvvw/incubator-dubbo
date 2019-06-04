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
package org.apache.dubbo.common.serialize;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serialization strategy interface that specifies a serializer. (SPI, Singleton, ThreadSafe)
 *
 * The default extension is hessian2 and the default serialization implementation of the dubbo protocol.
 * <pre>
 *     e.g. &lt;dubbo:protocol serialization="xxx" /&gt;
 * </pre>
 */
//该接口是序列化接口，该接口也是可扩展接口，默认是使用hessian2序列化方式。
// 其中定义了序列化和反序列化等方法
@SPI("hessian2")
public interface Serialization {

    /**
     * Get content type unique id, recommended that custom implementations use values greater than 20.
     *
     * @return content type id
     */
    //获取标识序列化类型的content-type的唯一id，推荐自定义实现使用的id大于20
    byte getContentTypeId();

    /**
     * Get content type
     * 得内容类型名
     * @return content type
     */
    String getContentType();

    /**
     * Get a serialization implementation instance
     * 创建 ObjectOutput 对象，序列化输出到 OutputStream
     * @param url URL address for the remote service
     * @param output the underlying output stream
     * @return serializer
     * @throws IOException
     */
    @Adaptive
    ObjectOutput serialize(URL url, OutputStream output) throws IOException;

    /**
     * Get a deserialization implementation instance
     * 创建 ObjectInput 对象，从 InputStream 反序列化
     * @param url URL address for the remote service
     * @param input the underlying input stream
     * @return deserializer
     * @throws IOException
     */
    @Adaptive
    ObjectInput deserialize(URL url, InputStream input) throws IOException;

}