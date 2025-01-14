/*
 * Copyright 2020 Telefonaktiebolaget LM Ericsson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ericsson.bss.cassandra.ecchronos.application.config;

public final class Security
{
    private CqlSecurity cql;
    private JmxSecurity jmx;

    public CqlSecurity getCql()
    {
        return cql;
    }

    public void setCql(final CqlSecurity aCQL)
    {
        this.cql = aCQL;
    }

    public JmxSecurity getJmx()
    {
        return jmx;
    }

    public void setJmx(final JmxSecurity aJMX)
    {
        this.jmx = aJMX;
    }

    public static final class CqlSecurity
    {
        private Credentials credentials;
        private TLSConfig tls;

        public Credentials getCredentials()
        {
            return credentials;
        }

        public void setCredentials(final Credentials theCredentials)
        {
            this.credentials = theCredentials;
        }

        public TLSConfig getTls()
        {
            return tls;
        }

        public void setTls(final TLSConfig aTLS)
        {
            this.tls = aTLS;
        }
    }

    public static final class JmxSecurity
    {
        private Credentials credentials;
        private TLSConfig tls;

        public Credentials getCredentials()
        {
            return credentials;
        }

        public void setCredentials(final Credentials theCredentials)
        {
            this.credentials = theCredentials;
        }

        public TLSConfig getTls()
        {
            return tls;
        }

        public void setTls(final TLSConfig aTLS)
        {
            this.tls = aTLS;
        }
    }
}
