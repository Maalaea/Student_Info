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
      return org.bitcoinj.protocols.channels.ClientState.internal_static_paym