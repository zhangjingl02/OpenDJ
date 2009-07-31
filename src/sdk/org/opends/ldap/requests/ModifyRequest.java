/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2009 Sun Microsystems, Inc.
 */

package org.opends.ldap.requests;



import java.util.ArrayList;
import java.util.List;

import org.opends.server.types.ByteString;
import org.opends.server.util.Validator;
import org.opends.types.RawAttribute;
import org.opends.types.Change;
import org.opends.types.DN;
import org.opends.types.ModificationType;



/**
 * A raw modify request.
 */
public final class ModifyRequest extends Request
{
  // The DN of the entry to be modified.
  private String dn;

  // The list of changes associated with this request.
  private final List<Change> changes = new ArrayList<Change>();



  /**
   * Creates a new raw modify request using the provided entry DN.
   * <p>
   * The new raw modify request will contain an empty list of controls,
   * and an empty list of modifications.
   *
   * @param dn
   *          The raw, unprocessed entry DN for this modify request.
   */
  public ModifyRequest(DN dn)
  {
    Validator.ensureNotNull(dn);
    this.dn = dn.toString();
  }



  /**
   * Creates a new raw modify request using the provided entry DN.
   * <p>
   * The new raw modify request will contain an empty list of controls,
   * and an empty list of modifications.
   *
   * @param dn
   *          The raw, unprocessed entry DN for this modify request.
   */
  public ModifyRequest(String dn)
  {
    Validator.ensureNotNull(dn);
    this.dn = dn;
  }



  /**
   * Adds the provided modification to the set of raw modifications for
   * this modify request.
   *
   * @param change
   * @return This raw modify request.
   */
  public ModifyRequest addChange(Change change)
  {
    Validator.ensureNotNull(change);
    changes.add(change);
    return this;
  }



  /**
   * Adds the provided modification to the set of raw modifications for
   * this modify request.
   *
   * @param modificationType
   * @param attribute
   * @return This raw modify request.
   */
  public ModifyRequest addChange(ModificationType modificationType,
      RawAttribute attribute)
  {
    Validator.ensureNotNull(modificationType, attribute);
    changes.add(new Change(modificationType, attribute));
    return this;
  }



  /**
   * Adds the provided modification to the set of raw modifications for
   * this modify request.
   *
   * @param modificationType
   * @param attributeDescription
   * @param attributeValue
   * @return This raw modify request.
   */
  public ModifyRequest addChange(ModificationType modificationType,
      String attributeDescription, ByteString... attributeValue)
  {
    Validator.ensureNotNull(modificationType, attributeDescription);
    changes.add(new Change(modificationType, attributeDescription,
        attributeValue));
    return this;
  }



  /**
   * Returns the list of modifications in their raw, unparsed form as
   * read from the client request.
   * <p>
   * Some of these modifications may be invalid as no validation will
   * have been performed on them. Any modifications made to the returned
   * modification {@code List} will be reflected in this modify request.
   *
   * @return The list of modifications in their raw, unparsed form as
   *         read from the client request.
   */
  public Iterable<Change> getChanges()
  {
    return changes;
  }



  /**
   * Returns the raw, unprocessed entry DN as included in the request
   * from the client.
   * <p>
   * This may or may not contain a valid DN, as no validation will have
   * been performed.
   *
   * @return The raw, unprocessed entry DN as included in the request
   *         from the client.
   */
  public String getDN()
  {
    return dn;
  }



  /**
   * Sets the raw, unprocessed entry DN for this modify request.
   * <p>
   * This may or may not contain a valid DN.
   *
   * @param dn
   *          The raw, unprocessed entry DN for this modify request.
   * @return This raw modify request.
   */
  public ModifyRequest setDN(DN dn)
  {
    Validator.ensureNotNull(dn);
    this.dn = dn.toString();
    return this;
  }



  /**
   * Sets the raw, unprocessed entry DN for this modify request.
   * <p>
   * This may or may not contain a valid DN.
   *
   * @param dn
   *          The raw, unprocessed entry DN for this modify request.
   * @return This raw modify request.
   */
  public ModifyRequest setDN(String dn)
  {
    Validator.ensureNotNull(dn);
    this.dn = dn;
    return this;
  }



  /**
   * {@inheritDoc}
   */
  @Override
  public void toString(StringBuilder buffer)
  {
    buffer.append("ModifyRequest(entry=");
    buffer.append(dn);
    buffer.append(", changes=");
    buffer.append(changes);
    buffer.append(", controls=");
    buffer.append(getControls());
    buffer.append(")");
  }

}
