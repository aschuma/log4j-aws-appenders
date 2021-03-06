// Copyright (c) Keith D Gregory
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.kdgregory.logging.aws.common;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.amazonaws.regions.Regions;

import com.kdgregory.logging.common.factories.ClientFactory;
import com.kdgregory.logging.common.util.InternalLogger;


/**
 *  Responsible for constructing an AWS client based on writer configuration.
 *  <p>
 *  The following three approaches are tried, in order:
 *  <ol>
 *  <li> Invoking a configured factory method via reflection.
 *  <li> Invoking the SDK default client builder via reflection, if it exists in
 *       the version of the SDK in use.
 *  <li> Invoking the SDK client constructor and configuring it using either a
 *       configured endpoint or the <code>AWS_REGION </code> environment variable.
 *  </ol>
 */
public class DefaultClientFactory<AWSClientType>
implements ClientFactory<AWSClientType>
{
    // these all come from the constructor
    private Class<AWSClientType> clientType;
    private String factoryMethodName;
    private String region;
    private String endpoint;
    private InternalLogger logger;

    // lookup tables for client constructors and factories
    // these are protected intance variables (rather than static) so that they
    // can be modified for testing

    protected Map<String,String> factoryClasses = new HashMap<String,String>();
    {
        factoryClasses.put("com.amazonaws.services.logs.AWSLogs",           "com.amazonaws.services.logs.AWSLogsClientBuilder");
        factoryClasses.put("com.amazonaws.services.kinesis.AmazonKinesis",  "com.amazonaws.services.kinesis.AmazonKinesisClientBuilder");
        factoryClasses.put("com.amazonaws.services.sns.AmazonSNS",          "com.amazonaws.services.sns.AmazonSNSClientBuilder");
    }

    protected Map<String,String> clientClasses = new HashMap<String,String>();
    {
        clientClasses.put("com.amazonaws.services.logs.AWSLogs",            "com.amazonaws.services.logs.AWSLogsClient");
        clientClasses.put("com.amazonaws.services.kinesis.AmazonKinesis",   "com.amazonaws.services.kinesis.AmazonKinesisClient");
        clientClasses.put("com.amazonaws.services.sns.AmazonSNS",           "com.amazonaws.services.sns.AmazonSNSClient");
    }


    /**
     *  @param clientType       The AWS client interface type, used for hardcoded selection chains.
     *  @param factoryMethod    Optional: if not-null, specifies a caller-defined static method to
     *                          create the client.
     *  @param region           Optional: if non-blank, specifies the desired AWS region for a client
     *                          created either via constructor or SDK builder.
     *  @param endpoint         Optional: if not null, specifies a caller-defined endpoint to apply
     *                          to a client created via the default constructor.
     *  @param logger           Used to log creation events/errors.
     */
    public DefaultClientFactory(Class<AWSClientType> clientType, String factoryMethod, String region, String endpoint, InternalLogger logger)
    {
        this.clientType = clientType;
        this.factoryMethodName = factoryMethod;
        this.region = region;
        this.endpoint = endpoint;
        this.logger = logger;
    }


    @Override
    public AWSClientType createClient()
    {
        AWSClientType client = tryFactory();
        if (client != null)
            return client;

        client = tryBuilder();
        if (client != null)
            return client;

        client = tryConstructor();
        if (client != null)
            return client;

        return null;
    }

//----------------------------------------------------------------------------
//  Internals -- all methods "protected" for overriding in unit tests
//----------------------------------------------------------------------------

    protected AWSClientType tryFactory()
    {
        if ((factoryMethodName == null) || factoryMethodName.isEmpty())
            return null;

        int methodIdx = factoryMethodName.lastIndexOf('.');
        if (methodIdx < 0)
            throw new IllegalArgumentException("invalid client factory configuration: " + factoryMethodName);

        logger.debug("creating client via factory method: " + factoryMethodName);

        try
        {
            Class<?> factoryKlass = Class.forName(factoryMethodName.substring(0, methodIdx));
            Method factoryMethod = factoryKlass.getDeclaredMethod(factoryMethodName.substring(methodIdx + 1));
            return clientType.cast(factoryMethod.invoke(null));
        }
        catch (Exception ex)
        {
            logger.error("failed to create client via configured factory: " + factoryMethodName, ex);
            return null;
        }
    }


    public AWSClientType tryBuilder()
    {
        try
        {
            // builder classes don't exist in the base SDK version so we have to be completely generic
            Object builder = createBuilder();
            if (builder == null)
                return null;

            logger.debug("creating client via SDK builder");

            if ((region != null) && ! region.isEmpty())
            {
                logger.debug("setting region: " + region);
                maybeSetAttribute(builder, "setRegion", region);
            }

            Method clientFactoryMethod = builder.getClass().getMethod("build");
            return clientType.cast(clientFactoryMethod.invoke(builder));
        }
        catch (Exception ex)
        {
            logger.error("failed to invoke builder", ex);
            return null;
        }
    }


    // this method is extracted so we can mock it in unit tests; plus, it's OK if the builder
    // doesn't exist (and it won't in the base build), so we need separate exception handling
    // from the invocation code
    protected Object createBuilder()
    {
        try
        {
            String builderClassName = factoryClasses.get(clientType.getName());
            if (builderClassName == null)
                return null;

            Class<?> builderClass = Class.forName(builderClassName);
            Method factoryMethod = builderClass.getMethod("standard");
            return factoryMethod.invoke(null);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }


    protected AWSClientType tryConstructor()
    {
        logger.debug("creating client via constructor");

        AWSClientType client = invokeConstructor();
        if (client == null)
            return null;

        if (maybeSetAttribute(client, "setEndpoint", endpoint))
        {
            logger.debug("setting endpoint: " + endpoint);
            return client;
        }

        if (maybeSetRegion(client, region) || maybeSetRegion(client, System.getenv("AWS_REGION")))
            return client;

        return client;
    }


    // this is extracted so that it can be overridden by tests
    protected AWSClientType invokeConstructor()
    {
        String clientClass = clientClasses.get(clientType.getName());
        if (clientClass == null)
        {
            // should never happen, unless we're called from outside the library
            logger.error("unsupported client type: " + clientType, null);
            return null;
        }

        try
        {
            Class<?> klass = Class.forName(clientClass);
            return (AWSClientType)klass.newInstance();
        }
        catch (Exception ex)
        {
            logger.error("failed to instantiate service client: " + clientClass, ex);
            return null;
        }
    }


    // setting region requires multiple steps, including an enum lookup, any of which can fail
    // since this is called from multiple places, it deserves its own function
    protected boolean maybeSetRegion(AWSClientType client, String value)
    {
        if (client == null)
            return false;

        if ((value == null) || value.isEmpty())
            return false;

        try
        {
            Regions resolvedRegion = Regions.fromName(value);
            logger.debug("setting region: " + value);
            return maybeSetAttribute(client, "configureRegion", resolvedRegion);
        }
        catch (IllegalArgumentException ex)
        {
            logger.error("unsupported/invalid region: " + value, null);
            return false;
        }
    }


    protected boolean maybeSetAttribute(Object client, String setterName, Object value)
    {
        if (value == null)
            return false;

        if ((value instanceof String) && ((String)value).isEmpty())
            return false;

        try
        {
            Method setter = client.getClass().getMethod(setterName, value.getClass());
            setter.invoke(client, value);
            return true;
        }
        catch (Exception ex)
        {
            // should only fail for invalid name/value; since we're calling internally, it's
            // an error if that happens
            logger.error("failed to set attribute: " + setterName + "(" + value + ")", ex);
            return false;
        }
    }
}
