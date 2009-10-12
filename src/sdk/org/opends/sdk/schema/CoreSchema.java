package org.opends.sdk.schema;

import static org.opends.sdk.schema.SchemaConstants.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opends.sdk.schema.matchingrules.AuthPasswordExactEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.BitStringEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.BooleanEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseExactEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseExactIA5EqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseExactIA5SubstringMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseExactOrderingMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseExactSubstringMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseIgnoreEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseIgnoreIA5EqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseIgnoreIA5SubstringMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseIgnoreListEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseIgnoreListSubstringMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseIgnoreOrderingMatchingRule;
import org.opends.sdk.schema.matchingrules.CaseIgnoreSubstringMatchingRule;
import org.opends.sdk.schema.matchingrules.DirectoryStringFirstComponentEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.DistinguishedNameEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.DoubleMetaphoneApproximateMatchingRule;
import org.opends.sdk.schema.matchingrules.GeneralizedTimeEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.GeneralizedTimeOrderingMatchingRule;
import org.opends.sdk.schema.matchingrules.IntegerEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.IntegerFirstComponentEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.IntegerOrderingMatchingRule;
import org.opends.sdk.schema.matchingrules.KeywordEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.NumericStringEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.NumericStringOrderingMatchingRule;
import org.opends.sdk.schema.matchingrules.NumericStringSubstringMatchingRule;
import org.opends.sdk.schema.matchingrules.ObjectIdentifierEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.ObjectIdentifierFirstComponentEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.OctetStringEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.OctetStringOrderingMatchingRule;
import org.opends.sdk.schema.matchingrules.OctetStringSubstringMatchingRule;
import org.opends.sdk.schema.matchingrules.PresentationAddressEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.ProtocolInformationEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.TelephoneNumberEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.TelephoneNumberSubstringMatchingRule;
import org.opends.sdk.schema.matchingrules.UUIDEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.UUIDOrderingMatchingRule;
import org.opends.sdk.schema.matchingrules.UniqueMemberEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.UserPasswordExactEqualityMatchingRule;
import org.opends.sdk.schema.matchingrules.WordEqualityMatchingRule;
import org.opends.sdk.schema.syntaxes.AttributeTypeSyntax;
import org.opends.sdk.schema.syntaxes.AuthPasswordSyntax;
import org.opends.sdk.schema.syntaxes.BinarySyntax;
import org.opends.sdk.schema.syntaxes.BitStringSyntax;
import org.opends.sdk.schema.syntaxes.BooleanSyntax;
import org.opends.sdk.schema.syntaxes.CertificateListSyntax;
import org.opends.sdk.schema.syntaxes.CertificatePairSyntax;
import org.opends.sdk.schema.syntaxes.CertificateSyntax;
import org.opends.sdk.schema.syntaxes.CountryStringSyntax;
import org.opends.sdk.schema.syntaxes.DITContentRuleSyntax;
import org.opends.sdk.schema.syntaxes.DITStructureRuleSyntax;
import org.opends.sdk.schema.syntaxes.DeliveryMethodSyntax;
import org.opends.sdk.schema.syntaxes.DirectoryStringSyntax;
import org.opends.sdk.schema.syntaxes.DistinguishedNameSyntax;
import org.opends.sdk.schema.syntaxes.EnhancedGuideSyntax;
import org.opends.sdk.schema.syntaxes.FacsimileNumberSyntax;
import org.opends.sdk.schema.syntaxes.FaxSyntax;
import org.opends.sdk.schema.syntaxes.GeneralizedTimeSyntax;
import org.opends.sdk.schema.syntaxes.GuideSyntax;
import org.opends.sdk.schema.syntaxes.IA5StringSyntax;
import org.opends.sdk.schema.syntaxes.IntegerSyntax;
import org.opends.sdk.schema.syntaxes.JPEGSyntax;
import org.opends.sdk.schema.syntaxes.LDAPSyntaxDescriptionSyntax;
import org.opends.sdk.schema.syntaxes.MatchingRuleSyntax;
import org.opends.sdk.schema.syntaxes.MatchingRuleUseSyntax;
import org.opends.sdk.schema.syntaxes.NameAndOptionalUIDSyntax;
import org.opends.sdk.schema.syntaxes.NameFormSyntax;
import org.opends.sdk.schema.syntaxes.NumericStringSyntax;
import org.opends.sdk.schema.syntaxes.OIDSyntax;
import org.opends.sdk.schema.syntaxes.ObjectClassSyntax;
import org.opends.sdk.schema.syntaxes.OctetStringSyntax;
import org.opends.sdk.schema.syntaxes.OtherMailboxSyntax;
import org.opends.sdk.schema.syntaxes.PostalAddressSyntax;
import org.opends.sdk.schema.syntaxes.PresentationAddressSyntax;
import org.opends.sdk.schema.syntaxes.PrintableStringSyntax;
import org.opends.sdk.schema.syntaxes.ProtocolInformationSyntax;
import org.opends.sdk.schema.syntaxes.SubstringAssertionSyntax;
import org.opends.sdk.schema.syntaxes.SupportedAlgorithmSyntax;
import org.opends.sdk.schema.syntaxes.TelephoneNumberSyntax;
import org.opends.sdk.schema.syntaxes.TeletexTerminalIdentifierSyntax;
import org.opends.sdk.schema.syntaxes.TelexNumberSyntax;
import org.opends.sdk.schema.syntaxes.UTCTimeSyntax;
import org.opends.sdk.schema.syntaxes.UUIDSyntax;
import org.opends.sdk.schema.syntaxes.UserPasswordSyntax;

public final class CoreSchema
{
  private static final Map<String, List<String>> X500_ORIGIN =
      Collections.singletonMap(SCHEMA_PROPERTY_ORIGIN,
          Collections.singletonList("X.500"));
  private static final Map<String, List<String>> RFC2252_ORIGIN =
      Collections.singletonMap(SCHEMA_PROPERTY_ORIGIN,
          Collections.singletonList("RFC 2252"));
  private static final Map<String, List<String>> RFC3045_ORIGIN =
      Collections.singletonMap(SCHEMA_PROPERTY_ORIGIN,
          Collections.singletonList("RFC 3045"));
  private static final Map<String, List<String>> RFC3112_ORIGIN =
      Collections.singletonMap(SCHEMA_PROPERTY_ORIGIN,
          Collections.singletonList("RFC 3112"));
  private static final Map<String, List<String>> RFC4512_ORIGIN =
      Collections.singletonMap(SCHEMA_PROPERTY_ORIGIN,
          Collections.singletonList("RFC 4512"));
  private static final Map<String, List<String>> RFC4517_ORIGIN =
      Collections.singletonMap(SCHEMA_PROPERTY_ORIGIN,
          Collections.singletonList("RFC 4517"));
  private static final Map<String, List<String>> RFC4519_ORIGIN =
      Collections.singletonMap(SCHEMA_PROPERTY_ORIGIN,
          Collections.singletonList("RFC 4519"));
  private static final Map<String, List<String>> RFC4530_ORIGIN =
      Collections.singletonMap(SCHEMA_PROPERTY_ORIGIN,
          Collections.singletonList("RFC 4530"));
  public static final Map<String, List<String>> OPENDS_ORIGIN =
      Collections.singletonMap(SCHEMA_PROPERTY_ORIGIN,
          Collections.singletonList("OpenDS Directory Server"));
  private static final String EMPTY_STRING = "".intern();
  private static final Set<String> EMPTY_STRING_SET =
      Collections.emptySet();

  private static final Schema SINGLETON;

  static
  {
    SchemaBuilder builder = new SchemaBuilder();
    defaultSyntaxes(builder);
    defaultMatchingRules(builder);
    defaultAttributeTypes(builder);
    defaultObjectClasses(builder);

    addRFC4519(builder);
    addRFC4530(builder);
    addRFC3045(builder);
    addRFC3112(builder);
    addSunProprietary(builder);

    SINGLETON = Schema.nonStrict(builder.toSchema());
  }

  public static Schema instance()
  {
    return SINGLETON;
  }

  private static void defaultSyntaxes(SchemaBuilder builder)
  {
    // All RFC 4512 / 4517
    builder.addSyntax(SYNTAX_ATTRIBUTE_TYPE_OID,
        SYNTAX_ATTRIBUTE_TYPE_DESCRIPTION,
        RFC4512_ORIGIN, new AttributeTypeSyntax(), false);
    builder.addSyntax(SYNTAX_BINARY_OID, SYNTAX_BINARY_DESCRIPTION,
        RFC4512_ORIGIN, new BinarySyntax(), false);
    builder.addSyntax(SYNTAX_BIT_STRING_OID, SYNTAX_BIT_STRING_DESCRIPTION,
        RFC4512_ORIGIN, new BitStringSyntax(), false);
    builder.addSyntax(SYNTAX_BOOLEAN_OID, SYNTAX_BOOLEAN_DESCRIPTION,
        RFC4512_ORIGIN, new BooleanSyntax(), false);
    builder.addSyntax(SYNTAX_CERTLIST_OID, SYNTAX_CERTLIST_DESCRIPTION,
        RFC4512_ORIGIN, new CertificateListSyntax(), false);
    builder.addSyntax(SYNTAX_CERTPAIR_OID, SYNTAX_CERTPAIR_DESCRIPTION,
        RFC4512_ORIGIN, new CertificatePairSyntax(), false);
    builder.addSyntax(SYNTAX_CERTIFICATE_OID, SYNTAX_CERTIFICATE_DESCRIPTION,
        RFC4512_ORIGIN, new CertificateSyntax(), false);
    builder.addSyntax(SYNTAX_COUNTRY_STRING_OID,
        SYNTAX_COUNTRY_STRING_DESCRIPTION,
        RFC4512_ORIGIN, new CountryStringSyntax(), false);
    builder.addSyntax(SYNTAX_DELIVERY_METHOD_OID,
        SYNTAX_DELIVERY_METHOD_DESCRIPTION,
        RFC4512_ORIGIN, new DeliveryMethodSyntax(), false);
    builder.addSyntax(SYNTAX_DIRECTORY_STRING_OID,
        SYNTAX_DIRECTORY_STRING_DESCRIPTION,
        RFC4512_ORIGIN, new DirectoryStringSyntax(false), false);
    builder.addSyntax(SYNTAX_DIT_CONTENT_RULE_OID,
        SYNTAX_DIT_CONTENT_RULE_DESCRIPTION,
        RFC4512_ORIGIN, new DITContentRuleSyntax(), false);
    builder.addSyntax(SYNTAX_DIT_STRUCTURE_RULE_OID,
        SYNTAX_DIT_STRUCTURE_RULE_DESCRIPTION,
        RFC4512_ORIGIN, new DITStructureRuleSyntax(), false);
    builder.addSyntax(SYNTAX_DN_OID, SYNTAX_DN_DESCRIPTION,
        RFC4512_ORIGIN, new DistinguishedNameSyntax(), false);
    builder.addSyntax(SYNTAX_ENHANCED_GUIDE_OID,
        SYNTAX_ENHANCED_GUIDE_DESCRIPTION,
        RFC4512_ORIGIN, new EnhancedGuideSyntax(), false);
    builder.addSyntax(SYNTAX_FAXNUMBER_OID, SYNTAX_FAXNUMBER_DESCRIPTION,
        RFC4512_ORIGIN, new FacsimileNumberSyntax(), false);
    builder.addSyntax(SYNTAX_FAX_OID, SYNTAX_FAX_DESCRIPTION,
        RFC4512_ORIGIN, new FaxSyntax(), false);
    builder.addSyntax(SYNTAX_GENERALIZED_TIME_OID,
        SYNTAX_GENERALIZED_TIME_DESCRIPTION,
        RFC4512_ORIGIN, new GeneralizedTimeSyntax(), false);
    builder.addSyntax(SYNTAX_GUIDE_OID, SYNTAX_GUIDE_DESCRIPTION,
        RFC4512_ORIGIN, new GuideSyntax(), false);
    builder.addSyntax(SYNTAX_IA5_STRING_OID, SYNTAX_IA5_STRING_DESCRIPTION,
        RFC4512_ORIGIN, new IA5StringSyntax(), false);
    builder.addSyntax(SYNTAX_INTEGER_OID, SYNTAX_INTEGER_DESCRIPTION,
        RFC4512_ORIGIN, new IntegerSyntax(), false);
    builder.addSyntax(SYNTAX_JPEG_OID, SYNTAX_JPEG_DESCRIPTION,
        RFC4512_ORIGIN, new JPEGSyntax(), false);
    builder.addSyntax(SYNTAX_MATCHING_RULE_OID,
        SYNTAX_MATCHING_RULE_DESCRIPTION,
        RFC4512_ORIGIN, new MatchingRuleSyntax(), false);
    builder.addSyntax(SYNTAX_MATCHING_RULE_USE_OID,
        SYNTAX_MATCHING_RULE_USE_DESCRIPTION,
        RFC4512_ORIGIN, new MatchingRuleUseSyntax(), false);
    builder.addSyntax(SYNTAX_LDAP_SYNTAX_OID, SYNTAX_LDAP_SYNTAX_DESCRIPTION,
        RFC4512_ORIGIN, new LDAPSyntaxDescriptionSyntax(), false);
    builder.addSyntax(SYNTAX_NAME_AND_OPTIONAL_UID_OID,
        SYNTAX_NAME_AND_OPTIONAL_UID_DESCRIPTION,
        RFC4517_ORIGIN, new NameAndOptionalUIDSyntax(), false);
    builder.addSyntax(SYNTAX_NAME_FORM_OID, SYNTAX_NAME_FORM_DESCRIPTION,
        RFC4512_ORIGIN, new NameFormSyntax(), false);
    builder.addSyntax(SYNTAX_NUMERIC_STRING_OID,
        SYNTAX_NUMERIC_STRING_DESCRIPTION,
        RFC4512_ORIGIN, new NumericStringSyntax(), false);
    builder.addSyntax(SYNTAX_OBJECTCLASS_OID, SYNTAX_OBJECTCLASS_DESCRIPTION,
        RFC4512_ORIGIN, new ObjectClassSyntax(), false);
    builder.addSyntax(SYNTAX_OCTET_STRING_OID, SYNTAX_OCTET_STRING_DESCRIPTION,
        RFC4512_ORIGIN, new OctetStringSyntax(), false);
    builder.addSyntax(SYNTAX_OID_OID, SYNTAX_OID_DESCRIPTION,
        RFC4512_ORIGIN, new OIDSyntax(), false);
    builder.addSyntax(SYNTAX_OTHER_MAILBOX_OID,
        SYNTAX_OTHER_MAILBOX_DESCRIPTION,
        RFC4512_ORIGIN, new OtherMailboxSyntax(), false);
    builder.addSyntax(SYNTAX_POSTAL_ADDRESS_OID,
        SYNTAX_POSTAL_ADDRESS_DESCRIPTION,
        RFC4512_ORIGIN, new PostalAddressSyntax(), false);
    // Depreciated in RFC 4512
    builder.addSyntax(SYNTAX_PRESENTATION_ADDRESS_OID,
        SYNTAX_PRESENTATION_ADDRESS_DESCRIPTION,
        RFC2252_ORIGIN, new PresentationAddressSyntax(), false);
    builder.addSyntax(SYNTAX_PRINTABLE_STRING_OID,
        SYNTAX_PRINTABLE_STRING_DESCRIPTION,
        RFC4512_ORIGIN, new PrintableStringSyntax(), false);
    // Depreciated in RFC 4512
    builder.addSyntax(SYNTAX_PROTOCOL_INFORMATION_OID,
        SYNTAX_PROTOCOL_INFORMATION_DESCRIPTION,
        RFC2252_ORIGIN, new ProtocolInformationSyntax(), false);
    builder.addSyntax(SYNTAX_SUBSTRING_ASSERTION_OID,
        SYNTAX_SUBSTRING_ASSERTION_DESCRIPTION,
        RFC4512_ORIGIN, new SubstringAssertionSyntax(), false);
    builder.addSyntax(SYNTAX_SUPPORTED_ALGORITHM_OID,
        SYNTAX_SUPPORTED_ALGORITHM_DESCRIPTION,
        RFC4512_ORIGIN, new SupportedAlgorithmSyntax(), false);
    builder.addSyntax(SYNTAX_TELEPHONE_OID, SYNTAX_TELEPHONE_DESCRIPTION,
        RFC4512_ORIGIN, new TelephoneNumberSyntax(false), false);
    builder.addSyntax(SYNTAX_TELETEX_TERM_ID_OID,
        SYNTAX_TELETEX_TERM_ID_DESCRIPTION,
        RFC4512_ORIGIN, new TeletexTerminalIdentifierSyntax(), false);
    builder.addSyntax(SYNTAX_TELEX_OID, SYNTAX_TELEX_DESCRIPTION,
        RFC4512_ORIGIN, new TelexNumberSyntax(), false);
    builder.addSyntax(SYNTAX_UTC_TIME_OID, SYNTAX_UTC_TIME_DESCRIPTION,
        RFC4512_ORIGIN, new UTCTimeSyntax(), false);
  }

  private static void defaultMatchingRules(SchemaBuilder builder)
  {
    builder.addMatchingRule(EMR_BIT_STRING_OID,
        Collections.singletonList(EMR_BIT_STRING_NAME),
        EMPTY_STRING, false, SYNTAX_BIT_STRING_OID, RFC4512_ORIGIN,
        new BitStringEqualityMatchingRule(), false);
    builder.addMatchingRule(EMR_BOOLEAN_OID,
        Collections.singletonList(EMR_BOOLEAN_NAME),
        EMPTY_STRING, false, SYNTAX_BOOLEAN_OID, RFC4512_ORIGIN,
        new BooleanEqualityMatchingRule(), false);
    builder.addMatchingRule(EMR_CASE_EXACT_IA5_OID,
        Collections.singletonList(EMR_CASE_EXACT_IA5_NAME),
        EMPTY_STRING, false, SYNTAX_IA5_STRING_OID, RFC4512_ORIGIN,
        new CaseExactIA5EqualityMatchingRule(), false);
    builder.addMatchingRule(SMR_CASE_EXACT_IA5_OID,
        Collections.singletonList(SMR_CASE_EXACT_IA5_NAME),
        EMPTY_STRING, false, SYNTAX_SUBSTRING_ASSERTION_OID,
        RFC4512_ORIGIN, new CaseExactIA5SubstringMatchingRule(), false);
    builder.addMatchingRule(EMR_CASE_EXACT_OID,
        Collections.singletonList(EMR_CASE_EXACT_NAME),
        EMPTY_STRING, false, SYNTAX_DIRECTORY_STRING_OID,
        RFC4512_ORIGIN, new CaseExactEqualityMatchingRule(), false);
    builder.addMatchingRule(OMR_CASE_EXACT_OID,
        Collections.singletonList(OMR_CASE_EXACT_NAME),
        EMPTY_STRING, false, SYNTAX_DIRECTORY_STRING_OID,
        RFC4512_ORIGIN, new CaseExactOrderingMatchingRule(), false);
    builder.addMatchingRule(SMR_CASE_EXACT_OID,
        Collections.singletonList(SMR_CASE_EXACT_NAME),
        EMPTY_STRING, false, SYNTAX_SUBSTRING_ASSERTION_OID,
        RFC4512_ORIGIN, new CaseExactSubstringMatchingRule(), false);
    builder.addMatchingRule(EMR_CASE_IGNORE_IA5_OID,
        Collections.singletonList(EMR_CASE_IGNORE_IA5_NAME),
        EMPTY_STRING, false, SYNTAX_IA5_STRING_OID,
        RFC4512_ORIGIN, new CaseIgnoreIA5EqualityMatchingRule(), false);
    builder.addMatchingRule(SMR_CASE_IGNORE_IA5_OID,
        Collections.singletonList(SMR_CASE_IGNORE_IA5_NAME),
        EMPTY_STRING, false, SYNTAX_SUBSTRING_ASSERTION_OID,
        RFC4512_ORIGIN, new CaseIgnoreIA5SubstringMatchingRule(), false);
    builder.addMatchingRule(EMR_CASE_IGNORE_LIST_OID,
        Collections.singletonList(EMR_CASE_IGNORE_LIST_NAME),
        EMPTY_STRING, false, SYNTAX_POSTAL_ADDRESS_OID,
        RFC4512_ORIGIN, new CaseIgnoreListEqualityMatchingRule(), false);
    builder.addMatchingRule(SMR_CASE_IGNORE_LIST_OID,
        Collections.singletonList(SMR_CASE_IGNORE_LIST_NAME),
        EMPTY_STRING, false, SYNTAX_SUBSTRING_ASSERTION_OID,
        RFC4512_ORIGIN, new CaseIgnoreListSubstringMatchingRule(), false);
    builder.addMatchingRule(EMR_CASE_IGNORE_OID,
        Collections.singletonList(EMR_CASE_IGNORE_NAME),
        EMPTY_STRING, false, SYNTAX_DIRECTORY_STRING_OID,
        RFC4512_ORIGIN, new CaseIgnoreEqualityMatchingRule(), false);
    builder.addMatchingRule(OMR_CASE_IGNORE_OID,
        Collections.singletonList(OMR_CASE_IGNORE_NAME),
        EMPTY_STRING, false,  SYNTAX_DIRECTORY_STRING_OID,
        RFC4512_ORIGIN, new CaseIgnoreOrderingMatchingRule(), false);
    builder.addMatchingRule(SMR_CASE_IGNORE_OID,
        Collections.singletonList(SMR_CASE_IGNORE_NAME),
        EMPTY_STRING, false, SYNTAX_SUBSTRING_ASSERTION_OID,
        RFC4512_ORIGIN, new CaseIgnoreSubstringMatchingRule(), false);
    builder.addMatchingRule(EMR_DIRECTORY_STRING_FIRST_COMPONENT_OID,
        Collections.singletonList(
            EMR_DIRECTORY_STRING_FIRST_COMPONENT_NAME), EMPTY_STRING, false,
        SYNTAX_DIRECTORY_STRING_OID,  RFC4512_ORIGIN,
        new DirectoryStringFirstComponentEqualityMatchingRule(), false);
    builder.addMatchingRule(EMR_DN_OID,
        Collections.singletonList(EMR_DN_NAME), EMPTY_STRING, false,
        SYNTAX_DN_OID, RFC4512_ORIGIN,
        new DistinguishedNameEqualityMatchingRule(), false);
    builder.addMatchingRule(EMR_GENERALIZED_TIME_OID,
        Collections.singletonList(EMR_GENERALIZED_TIME_NAME),
        EMPTY_STRING, false, SYNTAX_GENERALIZED_TIME_OID,
        RFC4512_ORIGIN, new GeneralizedTimeEqualityMatchingRule(), false);
    builder.addMatchingRule(OMR_GENERALIZED_TIME_OID,
        Collections.singletonList(OMR_GENERALIZED_TIME_NAME),
        EMPTY_STRING, false, SYNTAX_GENERALIZED_TIME_OID,
        RFC4512_ORIGIN, new GeneralizedTimeOrderingMatchingRule(), false);
    builder.addMatchingRule(EMR_INTEGER_FIRST_COMPONENT_OID,
        Collections.singletonList(EMR_INTEGER_FIRST_COMPONENT_NAME),
        EMPTY_STRING, false, SYNTAX_INTEGER_OID, RFC4512_ORIGIN,
        new IntegerFirstComponentEqualityMatchingRule(), false);
    builder.addMatchingRule(EMR_INTEGER_OID,
        Collections.singletonList(EMR_INTEGER_NAME),
        EMPTY_STRING, false, SYNTAX_INTEGER_OID, RFC4512_ORIGIN,
        new IntegerEqualityMatchingRule(), false);
    builder.addMatchingRule(OMR_INTEGER_OID,
        Collections.singletonList(OMR_INTEGER_NAME),
        EMPTY_STRING, false, SYNTAX_INTEGER_OID, RFC4512_ORIGIN,
        new IntegerOrderingMatchingRule(), false);
    builder.addMatchingRule(EMR_KEYWORD_OID,
        Collections.singletonList(EMR_KEYWORD_NAME),
        EMPTY_STRING, false, SYNTAX_DIRECTORY_STRING_OID,
        RFC4512_ORIGIN, new KeywordEqualityMatchingRule(), false);
    builder.addMatchingRule(EMR_NUMERIC_STRING_OID,
        Collections.singletonList(EMR_NUMERIC_STRING_NAME),
        EMPTY_STRING, false, SYNTAX_NUMERIC_STRING_OID,
        RFC4512_ORIGIN, new NumericStringEqualityMatchingRule(), false);
    builder.addMatchingRule(OMR_NUMERIC_STRING_OID,
        Collections.singletonList(OMR_NUMERIC_STRING_NAME),
        EMPTY_STRING, false, SYNTAX_NUMERIC_STRING_OID,
        RFC4512_ORIGIN, new NumericStringOrderingMatchingRule(), false);
    builder.addMatchingRule(SMR_NUMERIC_STRING_OID,
        Collections.singletonList(SMR_NUMERIC_STRING_NAME),
        EMPTY_STRING, false, SYNTAX_SUBSTRING_ASSERTION_OID,
        RFC4512_ORIGIN, new NumericStringSubstringMatchingRule(), false);
    builder.addMatchingRule(EMR_OID_FIRST_COMPONENT_OID,
        Collections.singletonList(EMR_OID_FIRST_COMPONENT_NAME),
        EMPTY_STRING, false, SYNTAX_OID_OID, RFC4512_ORIGIN,
        new ObjectIdentifierFirstComponentEqualityMatchingRule(), false);
    builder.addMatchingRule(EMR_OID_OID,
        Collections.singletonList(EMR_OID_NAME),
        EMPTY_STRING, false, SYNTAX_OID_OID, RFC4512_ORIGIN,
        new ObjectIdentifierEqualityMatchingRule(), false);
    builder.addMatchingRule(EMR_OCTET_STRING_OID,
        Collections.singletonList(EMR_OCTET_STRING_NAME),
        EMPTY_STRING, false, SYNTAX_OCTET_STRING_OID,
        RFC4512_ORIGIN, new OctetStringEqualityMatchingRule(), false);
    builder.addMatchingRule(OMR_OCTET_STRING_OID,
        Collections.singletonList(OMR_OCTET_STRING_NAME),
        EMPTY_STRING, false, SYNTAX_OCTET_STRING_OID,
        RFC4512_ORIGIN, new OctetStringOrderingMatchingRule(), false);
    // SMR octet string is not in any LDAP RFC and its from X.500
    builder.addMatchingRule(SMR_OCTET_STRING_OID,
        Collections.singletonList(SMR_OCTET_STRING_NAME),
        EMPTY_STRING, false, SYNTAX_OCTET_STRING_OID,
        X500_ORIGIN, new OctetStringSubstringMatchingRule(), false);
    // Depreciated in RFC 4512
    builder.addMatchingRule(EMR_PROTOCOL_INFORMATION_OID,
        Collections.singletonList(EMR_PROTOCOL_INFORMATION_NAME),
        EMPTY_STRING, false, SYNTAX_PROTOCOL_INFORMATION_OID,
        RFC2252_ORIGIN, new ProtocolInformationEqualityMatchingRule(), false);
    // Depreciated in RFC 4512
    builder.addMatchingRule(EMR_PRESENTATION_ADDRESS_OID,
        Collections.singletonList(EMR_PRESENTATION_ADDRESS_NAME),
        EMPTY_STRING, false, SYNTAX_PRESENTATION_ADDRESS_OID,
        RFC2252_ORIGIN, new PresentationAddressEqualityMatchingRule(), false);
    builder.addMatchingRule(EMR_TELEPHONE_OID,
        Collections.singletonList(EMR_TELEPHONE_NAME),
        EMPTY_STRING, false, SYNTAX_TELEPHONE_OID, RFC4512_ORIGIN,
        new TelephoneNumberEqualityMatchingRule(), false);
    builder.addMatchingRule(SMR_TELEPHONE_OID,
        Collections.singletonList(SMR_TELEPHONE_NAME),
        EMPTY_STRING, false, SYNTAX_SUBSTRING_ASSERTION_OID,
        RFC4512_ORIGIN, new TelephoneNumberSubstringMatchingRule(), false);
    builder.addMatchingRule(EMR_UNIQUE_MEMBER_OID,
        Collections.singletonList(EMR_UNIQUE_MEMBER_NAME),
        EMPTY_STRING, false, SYNTAX_NAME_AND_OPTIONAL_UID_OID,
        RFC4512_ORIGIN, new UniqueMemberEqualityMatchingRule(), false);
    builder.addMatchingRule(EMR_WORD_OID,
        Collections.singletonList(EMR_WORD_NAME),
        EMPTY_STRING, false, SYNTAX_DIRECTORY_STRING_OID,
        RFC4512_ORIGIN, new WordEqualityMatchingRule(), false);
  }

  private static void defaultAttributeTypes(SchemaBuilder builder)
  {
    builder.addAttributeType("2.5.4.0",
        Collections.singletonList("objectClass"),
        EMPTY_STRING,
        false,
        null,
        EMR_OID_NAME,
        null,
        null,
        null,
        SYNTAX_OID_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.4.1",
        Collections.singletonList("aliasedObjectName"),
        EMPTY_STRING,
        false,
        null,
        EMR_DN_NAME,
        null,
        null,
        null,
        SYNTAX_DN_OID,
        true,
        false,
        false,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.18.1",
        Collections.singletonList("createTimestamp"),
        EMPTY_STRING,
        false,
        null,
        EMR_GENERALIZED_TIME_NAME,
        OMR_GENERALIZED_TIME_NAME,
        null,
        null,
        SYNTAX_GENERALIZED_TIME_OID,
        true,
        false,
        true,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.18.2",
        Collections.singletonList("modifyTimestamp"),
        EMPTY_STRING,
        false,
        null,
        EMR_GENERALIZED_TIME_NAME,
        OMR_GENERALIZED_TIME_NAME,
        null,
        null,
        SYNTAX_GENERALIZED_TIME_OID,
        true,
        false,
        true,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.18.3",
        Collections.singletonList("creatorsName"),
        EMPTY_STRING,
        false,
        null,
        EMR_DN_NAME,
        null,
        null,
        null,
        SYNTAX_DN_OID,
        true,
        false,
        true,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.18.4",
        Collections.singletonList("modifiersName"),
        EMPTY_STRING,
        false,
        null,
        EMR_DN_NAME,
        null,
        null,
        null,
        SYNTAX_DN_OID,
        true,
        false,
        true,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.18.10",
        Collections.singletonList("subschemaSubentry"),
        EMPTY_STRING,
        false,
        null,
        EMR_DN_NAME,
        null,
        null,
        null,
        SYNTAX_DN_OID,
        true,
        false,
        true,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.21.5",
        Collections.singletonList("attributeTypes"),
        EMPTY_STRING,
        false,
        null,
        EMR_OID_FIRST_COMPONENT_NAME,
        null,
        null,
        null,
        SYNTAX_ATTRIBUTE_TYPE_OID,
        false,
        false,
        false,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.21.6",
        Collections.singletonList("objectClasses"),
        EMPTY_STRING,
        false,
        null,
        EMR_OID_FIRST_COMPONENT_NAME,
        null,
        null,
        null,
        SYNTAX_OBJECTCLASS_OID,
        false,
        false,
        false,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.21.4",
        Collections.singletonList("matchingRules"),
        EMPTY_STRING,
        false,
        null,
        EMR_OID_FIRST_COMPONENT_NAME,
        null,
        null,
        null,
        SYNTAX_MATCHING_RULE_OID,
        false,
        false,
        false,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.21.8",
        Collections.singletonList("matchingRuleUse"),
        EMPTY_STRING,
        false,
        null,
        EMR_OID_FIRST_COMPONENT_NAME,
        null,
        null,
        null,
        SYNTAX_MATCHING_RULE_USE_OID,
        false,
        false,
        false,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.21.9",
        Collections.singletonList("structuralObjectClass"),
        EMPTY_STRING,
        false,
        null,
        EMR_OID_NAME,
        null,
        null,
        null,
        SYNTAX_OID_OID,
        true,
        false,
        true,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.21.10",
        Collections.singletonList("governingStructureRule"),
        EMPTY_STRING,
        false,
        null,
        EMR_INTEGER_NAME,
        null,
        null,
        null,
        SYNTAX_INTEGER_OID,
        true,
        false,
        true,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("1.3.6.1.4.1.1466.101.120.5",
        Collections.singletonList("namingContexts"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_DN_OID,
        false,
        false,
        false,
        AttributeUsage.DSA_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("1.3.6.1.4.1.1466.101.120.6",
        Collections.singletonList("altServer"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_IA5_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.DSA_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("1.3.6.1.4.1.1466.101.120.7",
        Collections.singletonList("supportedExtension"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_OID_OID,
        false,
        false,
        false,
        AttributeUsage.DSA_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("1.3.6.1.4.1.1466.101.120.13",
        Collections.singletonList("supportedControl"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_OID_OID,
        false,
        false,
        false,
        AttributeUsage.DSA_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("1.3.6.1.4.1.1466.101.120.14",
        Collections.singletonList("supportedSASLMechanisms"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.DSA_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("1.3.6.1.4.1.4203.1.3.5",
        Collections.singletonList("supportedFeatures"),
        EMPTY_STRING,
        false,
        null,
        EMR_OID_NAME,
        null,
        null,
        null,
        SYNTAX_OID_OID,
        false,
        false,
        false,
        AttributeUsage.DSA_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("1.3.6.1.4.1.1466.101.120.15",
        Collections.singletonList("supportedLDAPVersion"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_INTEGER_OID,
        false,
        false,
        false,
        AttributeUsage.DSA_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("1.3.6.1.4.1.1466.101.120.16",
        Collections.singletonList("ldapSyntaxes"),
        EMPTY_STRING,
        false,
        null,
        EMR_OID_FIRST_COMPONENT_NAME,
        null,
        null,
        null,
        SYNTAX_LDAP_SYNTAX_OID,
        false,
        false,
        false,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.21.1",
        Collections.singletonList("dITStructureRules"),
        EMPTY_STRING,
        false,
        null,
        EMR_INTEGER_FIRST_COMPONENT_NAME,
        null,
        null,
        null,
        SYNTAX_DIT_STRUCTURE_RULE_OID,
        false,
        false,
        false,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.21.7",
        Collections.singletonList("nameForms"),
        EMPTY_STRING,
        false,
        null,
        EMR_OID_FIRST_COMPONENT_NAME,
        null,
        null,
        null,
        SYNTAX_NAME_FORM_OID,
        false,
        false,
        false,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);

    builder.addAttributeType("2.5.21.2",
        Collections.singletonList("dITContentRules"),
        EMPTY_STRING,
        false,
        null,
        EMR_OID_FIRST_COMPONENT_NAME,
        null,
        null,
        null,
        SYNTAX_DIT_CONTENT_RULE_OID,
        false,
        false,
        false,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4512_ORIGIN, false);
  }

  private static void defaultObjectClasses(SchemaBuilder builder)
  {
    builder.addObjectClass(TOP_OBJECTCLASS_OID,
        Collections.singletonList(TOP_OBJECTCLASS_NAME),
        TOP_OBJECTCLASS_DESCRIPTION,
        false,
        EMPTY_STRING_SET,
        Collections.singleton("objectClass"),
        EMPTY_STRING_SET,
        ObjectClassType.ABSTRACT,
        RFC4512_ORIGIN, false);

    builder.addObjectClass("2.5.6.1",
        Collections.singletonList("alias"),
        EMPTY_STRING,
        false,
        Collections.singleton("top"),
        Collections.singleton("aliasedObjectName"),
        EMPTY_STRING_SET,
        ObjectClassType.STRUCTURAL,
        RFC4512_ORIGIN, false);

    builder.addObjectClass(EXTENSIBLE_OBJECT_OBJECTCLASS_OID,
        Collections.singletonList(EXTENSIBLE_OBJECT_OBJECTCLASS_NAME),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        EMPTY_STRING_SET,
        EMPTY_STRING_SET,
        ObjectClassType.AUXILIARY,
        RFC4512_ORIGIN, false);

    Set<String> subschemaAttrs = new HashSet<String>();
    subschemaAttrs.add("dITStructureRules");
    subschemaAttrs.add("nameForms");
    subschemaAttrs.add("ditContentRules");
    subschemaAttrs.add("objectClasses");
    subschemaAttrs.add("attributeTypes");
    subschemaAttrs.add("matchingRules");
    subschemaAttrs.add("matchingRuleUse");

    builder.addObjectClass("2.5.20.1",
        Collections.singletonList("subschema"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        EMPTY_STRING_SET,
        subschemaAttrs,
        ObjectClassType.AUXILIARY,
        RFC4512_ORIGIN, false);
  }

  private static void addRFC4530(SchemaBuilder builder)
  {
    builder.addSyntax(SYNTAX_UUID_OID, SYNTAX_UUID_DESCRIPTION,
        RFC4530_ORIGIN, new UUIDSyntax(), false);
    builder.addMatchingRule(EMR_UUID_OID, Collections.singletonList(EMR_UUID_NAME),
        EMPTY_STRING, false, SYNTAX_UUID_OID, RFC4530_ORIGIN,
        new UUIDEqualityMatchingRule(), false);
    builder.addMatchingRule(OMR_UUID_OID, Collections.singletonList(OMR_UUID_NAME),
        EMPTY_STRING, false, SYNTAX_UUID_OID, RFC4530_ORIGIN,
        new UUIDOrderingMatchingRule(), false);
    builder.addAttributeType("1.3.6.1.1.16.4",
        Collections.singletonList("entryUUID"),
        "UUID of the entry",
        false,
        null,
        EMR_UUID_OID,
        OMR_UUID_OID,
        null,
        null,
        SYNTAX_UUID_OID,
        true,
        false,
        true,
        AttributeUsage.DIRECTORY_OPERATION,
        RFC4530_ORIGIN, false);
  }

  private static void addSunProprietary(SchemaBuilder builder)
  {
    builder.addSyntax(SYNTAX_USER_PASSWORD_OID, SYNTAX_USER_PASSWORD_DESCRIPTION,
        OPENDS_ORIGIN, new UserPasswordSyntax(), false);
    builder.addMatchingRule(EMR_USER_PASSWORD_EXACT_OID,
        Collections.singletonList(EMR_USER_PASSWORD_EXACT_NAME),
        EMR_USER_PASSWORD_EXACT_DESCRIPTION,
        false, SYNTAX_USER_PASSWORD_OID, OPENDS_ORIGIN,
        new UserPasswordExactEqualityMatchingRule(), false);
    builder.addMatchingRule(AMR_DOUBLE_METAPHONE_OID,
        Collections.singletonList(AMR_DOUBLE_METAPHONE_NAME),
        AMR_DOUBLE_METAPHONE_DESCRIPTION, false, SYNTAX_DIRECTORY_STRING_OID,
        OPENDS_ORIGIN, new DoubleMetaphoneApproximateMatchingRule(), false);

  }

  private static void addRFC3112(SchemaBuilder builder)
  {
    builder.addSyntax(SYNTAX_AUTH_PASSWORD_OID, SYNTAX_AUTH_PASSWORD_DESCRIPTION,
        RFC3112_ORIGIN, new AuthPasswordSyntax(), false);
    builder.addMatchingRule(EMR_AUTH_PASSWORD_EXACT_OID,
        Collections.singletonList(EMR_AUTH_PASSWORD_EXACT_NAME),
        EMR_AUTH_PASSWORD_EXACT_DESCRIPTION,
        false, SYNTAX_AUTH_PASSWORD_OID, RFC3112_ORIGIN,
        new AuthPasswordExactEqualityMatchingRule(), false);
    builder.addAttributeType("1.3.6.1.4.1.4203.1.3.3",
        Collections.singletonList("supportedAuthPasswordSchemes"),
        "supported password storage schemes",
        false,
        null,
        EMR_CASE_EXACT_IA5_OID,
        null,
        null,
        null,
        SYNTAX_IA5_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.DSA_OPERATION,
        RFC3112_ORIGIN, false);
    builder.addAttributeType("1.3.6.1.4.1.4203.1.3.4",
        Collections.singletonList("authPassword"),
        "password authentication information",
        false,
        null,
        EMR_AUTH_PASSWORD_EXACT_OID,
        null,
        null,
        null,
        SYNTAX_AUTH_PASSWORD_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC3112_ORIGIN, false);
    builder.addObjectClass("1.3.6.1.4.1.4203.1.4.7",
        Collections.singletonList("authPasswordObject"),
        "authentication password mix in class",
        false,
        EMPTY_STRING_SET,
        EMPTY_STRING_SET,
        Collections.singleton("authPassword"),
        ObjectClassType.AUXILIARY,
        RFC3112_ORIGIN, false);
  }

  private static void addRFC3045(SchemaBuilder builder)
  {
    builder.addAttributeType("1.3.6.1.1.4",
        Collections.singletonList("vendorName"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_EXACT_IA5_OID,
        null,
        null,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        true,
        false,
        true,
        AttributeUsage.DSA_OPERATION,
        RFC3045_ORIGIN, false);

    builder.addAttributeType("1.3.6.1.1.5",
        Collections.singletonList("vendorVersion"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_EXACT_IA5_OID,
        null,
        null,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        true,
        false,
        true,
        AttributeUsage.DSA_OPERATION,
        RFC3045_ORIGIN, false);
  }

  private static void addRFC4519(SchemaBuilder builder)
  {
    builder.addAttributeType("2.5.4.15",
        Collections.singletonList("businessCategory"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.6",
        Arrays.asList("c", "countryName"),
        EMPTY_STRING,
        false,
        "name",
        null,
        null,
        null,
        null,
        SYNTAX_COUNTRY_STRING_OID,
        true,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.3",
        Arrays.asList("cn", "commonName"),
        EMPTY_STRING,
        false,
        "name",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("0.9.2342.19200300.100.1.25",
        Arrays.asList("dc", "domainComponent"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_IA5_OID,
        null,
        SMR_CASE_IGNORE_IA5_OID,
        null,
        SYNTAX_IA5_STRING_OID,
        true,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.13",
        Collections.singletonList("description"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.27",
        Collections.singletonList("destinationIndicator"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_PRINTABLE_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.49",
        Collections.singletonList("distinguishedName"),
        EMPTY_STRING,
        false,
        null,
        EMR_DN_OID,
        null,
        null,
        null,
        SYNTAX_DN_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.46",
        Collections.singletonList("dnQualifier"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        OMR_CASE_IGNORE_OID,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_PRINTABLE_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.47",
        Collections.singletonList("enhancedSearchGuide"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_ENHANCED_GUIDE_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.23",
        Collections.singletonList("facsimileTelephoneNumber"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_FAXNUMBER_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.44",
        Collections.singletonList("generationQualifier"),
        EMPTY_STRING,
        false,
        "name",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.42",
        Collections.singletonList("givenName"),
        EMPTY_STRING,
        false,
        "name",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.51",
        Collections.singletonList("houseIdentifier"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.43",
        Collections.singletonList("initials"),
        EMPTY_STRING,
        false,
        "name",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.25",
        Collections.singletonList("internationalISDNNumber"),
        EMPTY_STRING,
        false,
        null,
        EMR_NUMERIC_STRING_OID,
        null,
        SMR_NUMERIC_STRING_OID,
        null,
        SYNTAX_NUMERIC_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.7",
        Arrays.asList("l", "localityName"),
        EMPTY_STRING,
        false,
        "name",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.31",
        Collections.singletonList("member"),
        EMPTY_STRING,
        false,
        "distinguishedName",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.41",
        Collections.singletonList("name"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.10",
        Arrays.asList("o", "organizationName"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.11",
        Arrays.asList("ou", "organizationalUnitName"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.32",
        Collections.singletonList("owner"),
        EMPTY_STRING,
        false,
        "distinguishedName",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.19",
        Collections.singletonList("physicalDeliveryOfficeName"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.16",
        Collections.singletonList("postalAddress"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.17",
        Collections.singletonList("postalCode"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.18",
        Collections.singletonList("postOfficeBox"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.28",
        Collections.singletonList("preferredDeliveryMethod"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_DELIVERY_METHOD_OID,
        true,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.26",
        Collections.singletonList("registeredAddress"),
        EMPTY_STRING,
        false,
        "postalAddress",
        null,
        null,
        null,
        null,
        SYNTAX_POSTAL_ADDRESS_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.33",
        Collections.singletonList("roleOccupant"),
        EMPTY_STRING,
        false,
        "distinguishedName",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.14",
        Collections.singletonList("searchGuide"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_GUIDE_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.34",
        Collections.singletonList("seeAlso"),
        EMPTY_STRING,
        false,
        "distinguishedName",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.5",
        Collections.singletonList("serialNumber"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_PRINTABLE_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.4",
        Arrays.asList("sn", "surname"),
        EMPTY_STRING,
        false,
        "name",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.8",
        Arrays.asList("st", "stateOrProvinceName"),
        EMPTY_STRING,
        false,
        "name",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.9",
        Arrays.asList("street", "streetAddress"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.20",
        Collections.singletonList("telephoneNumber"),
        EMPTY_STRING,
        false,
        null,
        EMR_TELEPHONE_OID,
        null,
        SMR_TELEPHONE_OID,
        null,
        SYNTAX_TELEPHONE_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.22",
        Collections.singletonList("teletexTerminalIdentifier"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_TELETEX_TERM_ID_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.21",
        Collections.singletonList("telexNumber"),
        EMPTY_STRING,
        false,
        null,
        null,
        null,
        null,
        null,
        SYNTAX_TELEX_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.12",
        Collections.singletonList("title"),
        EMPTY_STRING,
        false,
        "name",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("0.9.2342.19200300.100.1.1",
        Arrays.asList("uid", "userid"),
        EMPTY_STRING,
        false,
        null,
        EMR_CASE_IGNORE_OID,
        null,
        SMR_CASE_IGNORE_OID,
        null,
        SYNTAX_DIRECTORY_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.50",
        Collections.singletonList("uniqueMember"),
        EMPTY_STRING,
        false,
        null,
        EMR_UNIQUE_MEMBER_OID,
        null,
        null,
        null,
        SYNTAX_NAME_AND_OPTIONAL_UID_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.35",
        Collections.singletonList("userPassword"),
        EMPTY_STRING,
        false,
        null,
        EMR_OCTET_STRING_OID,
        null,
        null,
        null,
        SYNTAX_OCTET_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.24",
        Collections.singletonList("x121Address"),
        EMPTY_STRING,
        false,
        null,
        EMR_NUMERIC_STRING_OID,
        null,
        SMR_NUMERIC_STRING_OID,
        null,
        SYNTAX_NUMERIC_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    builder.addAttributeType("2.5.4.45",
        Collections.singletonList("x500UniqueIdentifier"),
        EMPTY_STRING,
        false,
        null,
        EMR_BIT_STRING_OID,
        null,
        null,
        null,
        SYNTAX_BIT_STRING_OID,
        false,
        false,
        false,
        AttributeUsage.USER_APPLICATIONS,
        RFC4519_ORIGIN, false);

    Set<String> attrs = new HashSet<String>();
    attrs.add("seeAlso");
    attrs.add("ou");
    attrs.add("l");
    attrs.add("description");

    builder.addObjectClass("2.5.6.11",
        Collections.singletonList("applicationProcess"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        Collections.singleton("cn"),
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);

    attrs = new HashSet<String>();
    attrs.add("searchGuide");
    attrs.add("description");

    builder.addObjectClass("2.5.6.2",
        Collections.singletonList("country"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        Collections.singleton("c"),
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);

    builder.addObjectClass("1.3.6.1.4.1.1466.344",
        Collections.singletonList("dcObject"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        Collections.singleton("dc"),
        EMPTY_STRING_SET,
        ObjectClassType.AUXILIARY,
        RFC4519_ORIGIN, false);

    attrs = new HashSet<String>();
    attrs.add("serialNumber");
    attrs.add("seeAlso");
    attrs.add("owner");
    attrs.add("ou");
    attrs.add("o");
    attrs.add("l");
    attrs.add("description");

    builder.addObjectClass("2.5.6.14",
        Collections.singletonList("device"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        Collections.singleton("cn"),
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);

    Set<String> must = new HashSet<String>();
    must.add("member");
    must.add("cn");

    attrs = new HashSet<String>();
    attrs.add("businessCategory");
    attrs.add("seeAlso");
    attrs.add("owner");
    attrs.add("ou");
    attrs.add("o");
    attrs.add("description");

    builder.addObjectClass("2.5.6.9",
        Collections.singletonList("groupOfNames"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        must,
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);



    attrs = new HashSet<String>();
    attrs.add("businessCategory");
    attrs.add("seeAlso");
    attrs.add("owner");
    attrs.add("ou");
    attrs.add("o");
    attrs.add("description");

    builder.addObjectClass("2.5.6.17",
        Collections.singletonList("groupOfUniqueNames"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        must,
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);

    attrs = new HashSet<String>();
    attrs.add("street");
    attrs.add("seeAlso");
    attrs.add("searchGuide");
    attrs.add("st");
    attrs.add("l");
    attrs.add("description");

    builder.addObjectClass("2.5.6.3",
        Collections.singletonList("locality"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        EMPTY_STRING_SET,
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);


    attrs = new HashSet<String>();
    attrs.add("userPassword");
    attrs.add("searchGuide");
    attrs.add("seeAlso");
    attrs.add("businessCategory");
    attrs.add("x121Address");
    attrs.add("registeredAddress");
    attrs.add("destinationIndicator");
    attrs.add("preferredDeliveryMethod");
    attrs.add("telexNumber");
    attrs.add("teletexTerminalIdentifier");
    attrs.add("telephoneNumber");
    attrs.add("internationalISDNNumber");
    attrs.add("facsimileTelephoneNumber");
    attrs.add("street");
    attrs.add("postOfficeBox");
    attrs.add("postalCode");
    attrs.add("postalAddress");
    attrs.add("physicalDeliveryOfficeName");
    attrs.add("st");
    attrs.add("l");
    attrs.add("description");

    builder.addObjectClass("2.5.6.4",
        Collections.singletonList("organization"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        Collections.singleton("o"),
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);

    attrs = new HashSet<String>();
    attrs.add("title");
    attrs.add("x121Address");
    attrs.add("registeredAddress");
    attrs.add("destinationIndicator");
    attrs.add("preferredDeliveryMethod");
    attrs.add("telexNumber");
    attrs.add("teletexTerminalIdentifier");
    attrs.add("telephoneNumber");
    attrs.add("internationalISDNNumber");
    attrs.add("facsimileTelephoneNumber");
    attrs.add("street");
    attrs.add("postOfficeBox");
    attrs.add("postalCode");
    attrs.add("postalAddress");
    attrs.add("physicalDeliveryOfficeName");
    attrs.add("ou");
    attrs.add("st");
    attrs.add("l");

    builder.addObjectClass("2.5.6.7",
        Collections.singletonList("organizationalPerson"),
        EMPTY_STRING,
        false,
        Collections.singleton("person"),
        EMPTY_STRING_SET,
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);

    attrs = new HashSet<String>();
    attrs.add("x121Address");
    attrs.add("registeredAddress");
    attrs.add("destinationIndicator");
    attrs.add("preferredDeliveryMethod");
    attrs.add("telexNumber");
    attrs.add("teletexTerminalIdentifier");
    attrs.add("telephoneNumber");
    attrs.add("internationalISDNNumber");
    attrs.add("facsimileTelephoneNumber");
    attrs.add("seeAlso");
    attrs.add("roleOccupant");
    attrs.add("preferredDeliveryMethod");
    attrs.add("street");
    attrs.add("postOfficeBox");
    attrs.add("postalCode");
    attrs.add("postalAddress");
    attrs.add("physicalDeliveryOfficeName");
    attrs.add("ou");
    attrs.add("st");
    attrs.add("l");
    attrs.add("description");

    builder.addObjectClass("2.5.6.8",
        Collections.singletonList("organizationalRole"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        Collections.singleton("cn"),
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);

    attrs = new HashSet<String>();
    attrs.add("businessCategory");
    attrs.add("description");
    attrs.add("destinationIndicator");
    attrs.add("facsimileTelephoneNumber");
    attrs.add("internationalISDNNumber");
    attrs.add("l");
    attrs.add("physicalDeliveryOfficeName");
    attrs.add("postalAddress");
    attrs.add("postalCode");
    attrs.add("postOfficeBox");
    attrs.add("preferredDeliveryMethod");
    attrs.add("registeredAddress");
    attrs.add("searchGuide");
    attrs.add("seeAlso");
    attrs.add("st");
    attrs.add("street");
    attrs.add("telephoneNumber");
    attrs.add("teletexTerminalIdentifier");
    attrs.add("telexNumber");
    attrs.add("userPassword");
    attrs.add("x121Address");

    builder.addObjectClass("2.5.6.5",
        Collections.singletonList("organizationalUnit"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        Collections.singleton("ou"),
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);

    must = new HashSet<String>();
    must.add("sn");
    must.add("cn");

    attrs = new HashSet<String>();
    attrs.add("userPassword");
    attrs.add("telephoneNumber");
    attrs.add("destinationIndicator");
    attrs.add("seeAlso");
    attrs.add("description");

    builder.addObjectClass("2.5.6.6",
        Collections.singletonList("person"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        must,
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);

    attrs = new HashSet<String>();
    attrs.add("businessCategory");
    attrs.add("x121Address");
    attrs.add("registeredAddress");
    attrs.add("destinationIndicator");
    attrs.add("preferredDeliveryMethod");
    attrs.add("telexNumber");
    attrs.add("teletexTerminalIdentifier");
    attrs.add("telephoneNumber");
    attrs.add("internationalISDNNumber");
    attrs.add("facsimileTelephoneNumber");
    attrs.add("preferredDeliveryMethod");
    attrs.add("street");
    attrs.add("postOfficeBox");
    attrs.add("postalCode");
    attrs.add("postalAddress");
    attrs.add("physicalDeliveryOfficeName");
    attrs.add("st");
    attrs.add("l");

    builder.addObjectClass("2.5.6.10",
        Collections.singletonList("residentialPerson"),
        EMPTY_STRING,
        false,
        Collections.singleton("person"),
        Collections.singleton("l"),
        attrs,
        ObjectClassType.STRUCTURAL,
        RFC4519_ORIGIN, false);

    builder.addObjectClass("1.3.6.1.1.3.1",
        Collections.singletonList("uidObject"),
        EMPTY_STRING,
        false,
        Collections.singleton(TOP_OBJECTCLASS_NAME),
        Collections.singleton("uid"),
        attrs,
        ObjectClassType.AUXILIARY,
        RFC4519_ORIGIN, false);
  }
}
