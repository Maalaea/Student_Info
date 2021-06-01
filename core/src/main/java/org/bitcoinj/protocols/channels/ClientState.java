// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: storedclientpaymentchannel.proto

package org.bitcoinj.protocols.channels;

public final class ClientState {
  private ClientState() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
  }
  public interface StoredClientPaymentChannelsOrBuilder extends
      // @@protoc_insertion_point(interface_extends:paymentchannels.StoredClientPaymentChannels)
      com.google.protobuf.MessageOrBuilder {

    /**
     * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
     */
    java.util.List<org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel> 
        getChannelsList();
    /**
     * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
     */
    org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel getChannels(int index);
    /**
     * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
     */
    int getChannelsCount();
    /**
     * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
     */
    java.util.List<? extends org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannelOrBuilder> 
        getChannelsOrBuilderList();
    /**
     * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
     */
    org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannelOrBuilder getChannelsOrBuilder(
        int index);
  }
  /**
   * Protobuf type {@code paymentchannels.StoredClientPaymentChannels}
   *
   * <pre>
   * A set of StoredPaymentChannel's
   * </pre>
   */
  public static final class StoredClientPaymentChannels extends
      com.google.protobuf.GeneratedMessage implements
      // @@protoc_insertion_point(message_implements:paymentchannels.StoredClientPaymentChannels)
      StoredClientPaymentChannelsOrBuilder {
    // Use StoredClientPaymentChannels.newBuilder() to construct.
    private StoredClientPaymentChannels(com.google.protobuf.GeneratedMessage.Builder<?> builder) {
      super(builder);
      this.unknownFields = builder.getUnknownFields();
    }
    private StoredClientPaymentChannels(boolean noInit) { this.unknownFields = com.google.protobuf.UnknownFieldSet.getDefaultInstance(); }

    private static final StoredClientPaymentChannels defaultInstance;
    public static StoredClientPaymentChannels getDefaultInstance() {
      return defaultInstance;
    }

    public StoredClientPaymentChannels getDefaultInstanceForType() {
      return defaultInstance;
    }

    private final com.google.protobuf.UnknownFieldSet unknownFields;
    @java.lang.Override
    public final com.google.protobuf.UnknownFieldSet
        getUnknownFields() {
      return this.unknownFields;
    }
    private StoredClientPaymentChannels(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      initFields();
      int mutable_bitField0_ = 0;
      com.google.protobuf.UnknownFieldSet.Builder unknownFields =
          com.google.protobuf.UnknownFieldSet.newBuilder();
      try {
        boolean done = false;
        while (!done) {
          int tag = input.readTag();
          switch (tag) {
            case 0:
              done = true;
              break;
            default: {
              if (!parseUnknownField(input, unknownFields,
                                     extensionRegistry, tag)) {
                done = true;
              }
              break;
            }
            case 10: {
              if (!((mutable_bitField0_ & 0x00000001) == 0x00000001)) {
                channels_ = new java.util.ArrayList<org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel>();
                mutable_bitField0_ |= 0x00000001;
              }
              channels_.add(input.readMessage(org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.PARSER, extensionRegistry));
              break;
            }
          }
        }
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        throw e.setUnfinishedMessage(this);
      } catch (java.io.IOException e) {
        throw new com.google.protobuf.InvalidProtocolBufferException(
            e.getMessage()).setUnfinishedMessage(this);
      } finally {
        if (((mutable_bitField0_ & 0x00000001) == 0x00000001)) {
          channels_ = java.util.Collections.unmodifiableList(channels_);
        }
        this.unknownFields = unknownFields.build();
        makeExtensionsImmutable();
      }
    }
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return org.bitcoinj.protocols.channels.ClientState.internal_static_paymentchannels_StoredClientPaymentChannels_descriptor;
    }

    protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return org.bitcoinj.protocols.channels.ClientState.internal_static_paymentchannels_StoredClientPaymentChannels_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels.class, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels.Builder.class);
    }

    public static com.google.protobuf.Parser<StoredClientPaymentChannels> PARSER =
        new com.google.protobuf.AbstractParser<StoredClientPaymentChannels>() {
      public StoredClientPaymentChannels parsePartialFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws com.google.protobuf.InvalidProtocolBufferException {
        return new StoredClientPaymentChannels(input, extensionRegistry);
      }
    };

    @java.lang.Override
    public com.google.protobuf.Parser<StoredClientPaymentChannels> getParserForType() {
      return PARSER;
    }

    public static final int CHANNELS_FIELD_NUMBER = 1;
    private java.util.List<org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel> channels_;
    /**
     * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
     */
    public java.util.List<org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel> getChannelsList() {
      return channels_;
    }
    /**
     * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
     */
    public java.util.List<? extends org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannelOrBuilder> 
        getChannelsOrBuilderList() {
      return channels_;
    }
    /**
     * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
     */
    public int getChannelsCount() {
      return channels_.size();
    }
    /**
     * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
     */
    public org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel getChannels(int index) {
      return channels_.get(index);
    }
    /**
     * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
     */
    public org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannelOrBuilder getChannelsOrBuilder(
        int index) {
      return channels_.get(index);
    }

    private void initFields() {
      channels_ = java.util.Collections.emptyList();
    }
    private byte memoizedIsInitialized = -1;
    public final boolean isInitialized() {
      byte isInitialized = memoizedIsInitialized;
      if (isInitialized == 1) return true;
      if (isInitialized == 0) return false;

      for (int i = 0; i < getChannelsCount(); i++) {
        if (!getChannels(i).isInitialized()) {
          memoizedIsInitialized = 0;
          return false;
        }
      }
      memoizedIsInitialized = 1;
      return true;
    }

    public void writeTo(com.google.protobuf.CodedOutputStream output)
                        throws java.io.IOException {
      getSerializedSize();
      for (int i = 0; i < channels_.size(); i++) {
        output.writeMessage(1, channels_.get(i));
      }
      getUnknownFields().writeTo(output);
    }

    private int memoizedSerializedSize = -1;
    public int getSerializedSize() {
      int size = memoizedSerializedSize;
      if (size != -1) return size;

      size = 0;
      for (int i = 0; i < channels_.size(); i++) {
        size += com.google.protobuf.CodedOutputStream
          .computeMessageSize(1, channels_.get(i));
      }
      size += getUnknownFields().getSerializedSize();
      memoizedSerializedSize = size;
      return size;
    }

    private static final long serialVersionUID = 0L;
    @java.lang.Override
    protected java.lang.Object writeReplace()
        throws java.io.ObjectStreamException {
      return super.writeReplace();
    }

    public static org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parseFrom(
        com.google.protobuf.ByteString data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parseFrom(
        com.google.protobuf.ByteString data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parseFrom(byte[] data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parseFrom(
        byte[] data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return PARSER.parseFrom(input);
    }
    public static org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parseFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseFrom(input, extensionRegistry);
    }
    public static org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return PARSER.parseDelimitedFrom(input);
    }
    public static org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parseDelimitedFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseDelimitedFrom(input, extensionRegistry);
    }
    public static org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parseFrom(
        com.google.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return PARSER.pars