/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opendj3/legal-notices/CDDLv1_0.txt.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2012 ForgeRock AS
 */

package org.forgerock.opendj.ldap;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import org.forgerock.opendj.ldap.requests.BindRequest;
import org.forgerock.opendj.ldap.requests.Requests;
import org.forgerock.opendj.ldap.responses.Responses;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.Test;

import com.forgerock.opendj.util.CompletedFutureResult;

/**
 * Tests the connection pool implementation..
 */
public class ConnectionPoolTestCase extends SdkTestCase
{

  /**
   * A connection event listener registered against a pooled connection should
   * be notified when the pooled connection is closed, NOT when the underlying
   * connection is closed.
   *
   * @throws Exception
   *           If an unexpected error occurred.
   */
  @Test
  public void testConnectionEventListenerClose() throws Exception
  {
    // TODO
  }

  /**
   * A connection event listener registered against a pooled connection should
   * be notified whenever an error occurs on the underlying connection.
   *
   * @throws Exception
   *           If an unexpected error occurred.
   */
  @Test
  public void testConnectionEventListenerError() throws Exception
  {
    // TODO
  }

  /**
   * A connection event listener registered against a pooled connection should
   * be notified whenever an unsolicited notification is received on the
   * underlying connection.
   *
   * @throws Exception
   *           If an unexpected error occurred.
   */
  @Test
  public void testConnectionEventListenerUnsolicitedNotification()
      throws Exception
  {
    // TODO
  }

  /**
   * Test basic pool functionality:
   * <ul>
   * <li>create a pool of size 2
   * <li>get 2 connections and make sure that they are usable
   * <li>close the pooled connections and check that the underlying connections
   * are not closed
   * <li>close the pool and check that underlying connections are closed.
   * </ul>
   *
   * @throws Exception
   *           If an unexpected error occurred.
   */
  @Test
  public void testConnectionLifeCycle() throws Exception
  {
    // Setup.
    final BindRequest bind1 =
        Requests.newSimpleBindRequest("cn=test1", "password".toCharArray());
    final Connection connection1 = mock(Connection.class);
    when(connection1.bind(bind1)).thenReturn(
        Responses.newBindResult(ResultCode.SUCCESS));
    when(connection1.isValid()).thenReturn(true);

    final BindRequest bind2 =
        Requests.newSimpleBindRequest("cn=test2", "password".toCharArray());
    final Connection connection2 = mock(Connection.class);
    when(connection2.bind(bind2)).thenReturn(
        Responses.newBindResult(ResultCode.SUCCESS));
    when(connection2.isValid()).thenReturn(true);

    final ConnectionFactory factory =
        mockConnectionFactory(connection1, connection2);
    final ConnectionPool pool = Connections.newFixedConnectionPool(factory, 2);

    verifyZeroInteractions(factory);
    verifyZeroInteractions(connection1);
    verifyZeroInteractions(connection2);

    /*
     * Obtain connections and verify that the correct underlying connection is
     * used. We can check the returned connection directly since it is a
     * wrapper, so we'll route a bind request instead and check that the
     * underlying connection got it.
     */
    final Connection pc1 = pool.getConnection();
    assertThat(pc1.bind(bind1).getResultCode()).isEqualTo(ResultCode.SUCCESS);
    verify(factory, times(1)).getConnection();
    verify(connection1).bind(bind1);
    verifyZeroInteractions(connection2);

    final Connection pc2 = pool.getConnection();
    assertThat(pc2.bind(bind2).getResultCode()).isEqualTo(ResultCode.SUCCESS);
    verify(factory, times(2)).getConnection();
    verifyNoMoreInteractions(connection1);
    verify(connection2).bind(bind2);

    // Release pooled connections (should not close underlying connection).
    pc1.close();
    assertThat(pc1.isValid()).isFalse();
    assertThat(pc1.isClosed()).isTrue();
    verify(connection1, times(0)).close();

    pc2.close();
    assertThat(pc2.isValid()).isFalse();
    assertThat(pc2.isClosed()).isTrue();
    verify(connection2, times(0)).close();

    // Close the pool (should close underlying connections).
    pool.close();
    verify(connection1).close();
    verify(connection2).close();
  }

  /**
   * Test behavior of pool at capacity.
   * <ul>
   * <li>create a pool of size 2
   * <li>get 2 connections and make sure that they are usable
   * <li>attempt to get third connection asynchronously and verify that no
   * connection was available
   * <li>release (close) a pooled connection
   * <li>verify that third attempt now completes.
   * </ul>
   *
   * @throws Exception
   *           If an unexpected error occurred.
   */
  @Test
  public void testGetConnectionAtCapacity() throws Exception
  {
    // Setup.
    final Connection connection1 = mock(Connection.class);
    when(connection1.isValid()).thenReturn(true);

    final BindRequest bind2 =
        Requests.newSimpleBindRequest("cn=test2", "password".toCharArray());
    final Connection connection2 = mock(Connection.class);
    when(connection2.bind(bind2)).thenReturn(
        Responses.newBindResult(ResultCode.SUCCESS));
    when(connection2.isValid()).thenReturn(true);

    final ConnectionFactory factory =
        mockConnectionFactory(connection1, connection2);
    final ConnectionPool pool = Connections.newFixedConnectionPool(factory, 2);

    // Fully utilize the pool.
    final Connection pc1 = pool.getConnection();
    final Connection pc2 = pool.getConnection();

    /*
     * Grab another connection and check that this attempt blocks (if there is a
     * connection available immediately then the future will be completed
     * immediately).
     */
    final FutureResult<Connection> future = pool.getConnectionAsync(null);
    assertThat(future.isDone()).isFalse();

    // Release a connection and verify that it is immediately redeemed by
    // the future.
    pc2.close();
    assertThat(future.isDone()).isTrue();

    // Check that returned connection routes request to released connection.
    final Connection pc3 = future.get();
    assertThat(pc3.bind(bind2).getResultCode()).isEqualTo(ResultCode.SUCCESS);
    verify(factory, times(2)).getConnection();
    verify(connection2).bind(bind2);

    pc1.close();
    pc2.close();
    pool.close();
  }

  /**
   * Verifies that stale connections which have become invalid while in use are
   * not placed back in the pool after being closed.
   *
   * @throws Exception
   *           If an unexpected error occurred.
   */
  @Test
  public void testSkipStaleConnectionsOnClose() throws Exception
  {
    // Setup.
    final Connection connection1 = mock(Connection.class);
    when(connection1.isValid()).thenReturn(true);

    final BindRequest bind2 =
        Requests.newSimpleBindRequest("cn=test2", "password".toCharArray());
    final Connection connection2 = mock(Connection.class);
    when(connection2.bind(bind2)).thenReturn(
        Responses.newBindResult(ResultCode.SUCCESS));
    when(connection2.isValid()).thenReturn(true);

    final ConnectionFactory factory =
        mockConnectionFactory(connection1, connection2);
    final ConnectionPool pool = Connections.newFixedConnectionPool(factory, 2);

    /*
     * Simulate remote disconnect of connection1 while application is using the
     * pooled connection. The pooled connection should be recycled immediately
     * on release.
     */
    final Connection pc1 = pool.getConnection();
    when(connection1.isValid()).thenReturn(false);
    assertThat(connection1.isValid()).isFalse();
    assertThat(pc1.isValid()).isFalse();
    pc1.close();
    assertThat(connection1.isValid()).isFalse();
    verify(connection1).close();

    // Now get another connection and check that it is ok.
    final Connection pc2 = pool.getConnection();
    assertThat(pc2.isValid()).isTrue();
    assertThat(pc2.bind(bind2).getResultCode()).isEqualTo(ResultCode.SUCCESS);
    verify(factory, times(2)).getConnection();
    verify(connection2).bind(bind2);

    pc2.close();
    pool.close();
  }

  /**
   * Verifies that stale connections which have become invalid while cached in
   * the internal pool are not returned to the caller. This may occur when an
   * idle pooled connection is disconnect by the remote server after a timeout.
   * The connection pool must detect that the pooled connection is invalid in
   * order to avoid returning it to the caller.
   * <p>
   * See issue OPENDJ-590.
   *
   * @throws Exception
   *           If an unexpected error occurred.
   */
  @Test
  public void testSkipStaleConnectionsOnGet() throws Exception
  {
    // Setup.
    final Connection connection1 = mock(Connection.class);
    when(connection1.isValid()).thenReturn(true);

    final BindRequest bind2 =
        Requests.newSimpleBindRequest("cn=test2", "password".toCharArray());
    final Connection connection2 = mock(Connection.class);
    when(connection2.bind(bind2)).thenReturn(
        Responses.newBindResult(ResultCode.SUCCESS));
    when(connection2.isValid()).thenReturn(true);

    final ConnectionFactory factory =
        mockConnectionFactory(connection1, connection2);
    final ConnectionPool pool = Connections.newFixedConnectionPool(factory, 2);

    // Get and release a single connection.
    pool.getConnection().close();

    /*
     * Simulate remote disconnect of connection1. The next connection attempt
     * should return connection2.
     */
    when(connection1.isValid()).thenReturn(false);
    assertThat(connection1.isValid()).isFalse();
    assertThat(connection2.isValid()).isTrue();
    final Connection pc = pool.getConnection();

    // Check that returned connection routes request to connection2.
    assertThat(pc.isValid()).isTrue();
    verify(connection1).close();
    assertThat(pc.bind(bind2).getResultCode()).isEqualTo(ResultCode.SUCCESS);
    verify(factory, times(2)).getConnection();
    verify(connection2).bind(bind2);

    pc.close();
    pool.close();
  }

  @SuppressWarnings("unchecked")
  private ConnectionFactory mockConnectionFactory(final Connection first,
      final Connection... remaining) throws Exception
  {
    final ConnectionFactory factory = mock(ConnectionFactory.class);
    when(factory.getConnection()).thenReturn(first, remaining);
    when(factory.getConnectionAsync(any(ResultHandler.class))).thenAnswer(
        new Answer<FutureResult<Connection>>()
        {
          @Override
          public FutureResult<Connection> answer(
              final InvocationOnMock invocation) throws Throwable
          {
            final Connection connection = factory.getConnection();
            // Execute handler and return future.
            final ResultHandler<? super Connection> handler =
                (ResultHandler<? super Connection>) invocation.getArguments()[0];
            if (handler != null)
            {
              handler.handleResult(connection);
            }
            return new CompletedFutureResult<Connection>(connection);
          }
        });
    return factory;
  }

}
