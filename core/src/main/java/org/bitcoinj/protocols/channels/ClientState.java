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
      return PARSER.parseFrom(input);
    }
    public static org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parseFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseFrom(input, extensionRegistry);
    }

    public static Builder newBuilder() { return Builder.create(); }
    public Builder newBuilderForType() { return newBuilder(); }
    public static Builder newBuilder(org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels prototype) {
      return newBuilder().mergeFrom(prototype);
    }
    public Builder toBuilder() { return newBuilder(this); }

    @java.lang.Override
    protected Builder newBuilderForType(
        com.google.protobuf.GeneratedMessage.BuilderParent parent) {
      Builder builder = new Builder(parent);
      return builder;
    }
    /**
     * Protobuf type {@code paymentchannels.StoredClientPaymentChannels}
     *
     * <pre>
     * A set of StoredPaymentChannel's
     * </pre>
     */
    public static final class Builder extends
        com.google.protobuf.GeneratedMessage.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:paymentchannels.StoredClientPaymentChannels)
        org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannelsOrBuilder {
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

      // Construct using org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels.newBuilder()
      private Builder() {
        maybeForceBuilderInitialization();
      }

      private Builder(
          com.google.protobuf.GeneratedMessage.BuilderParent parent) {
        super(parent);
        maybeForceBuilderInitialization();
      }
      private void maybeForceBuilderInitialization() {
        if (com.google.protobuf.GeneratedMessage.alwaysUseFieldBuilders) {
          getChannelsFieldBuilder();
        }
      }
      private static Builder create() {
        return new Builder();
      }

      public Builder clear() {
        super.clear();
        if (channelsBuilder_ == null) {
          channels_ = java.util.Collections.emptyList();
          bitField0_ = (bitField0_ & ~0x00000001);
        } else {
          channelsBuilder_.clear();
        }
        return this;
      }

      public Builder clone() {
        return create().mergeFrom(buildPartial());
      }

      public com.google.protobuf.Descriptors.Descriptor
          getDescriptorForType() {
        return org.bitcoinj.protocols.channels.ClientState.internal_static_paymentchannels_StoredClientPaymentChannels_descriptor;
      }

      public org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels getDefaultInstanceForType() {
        return org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels.getDefaultInstance();
      }

      public org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels build() {
        org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      public org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels buildPartial() {
        org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels result = new org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels(this);
        int from_bitField0_ = bitField0_;
        if (channelsBuilder_ == null) {
          if (((bitField0_ & 0x00000001) == 0x00000001)) {
            channels_ = java.util.Collections.unmodifiableList(channels_);
            bitField0_ = (bitField0_ & ~0x00000001);
          }
          result.channels_ = channels_;
        } else {
          result.channels_ = channelsBuilder_.build();
        }
        onBuilt();
        return result;
      }

      public Builder mergeFrom(com.google.protobuf.Message other) {
        if (other instanceof org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels) {
          return mergeFrom((org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels other) {
        if (other == org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels.getDefaultInstance()) return this;
        if (channelsBuilder_ == null) {
          if (!other.channels_.isEmpty()) {
            if (channels_.isEmpty()) {
              channels_ = other.channels_;
              bitField0_ = (bitField0_ & ~0x00000001);
            } else {
              ensureChannelsIsMutable();
              channels_.addAll(other.channels_);
            }
            onChanged();
          }
        } else {
          if (!other.channels_.isEmpty()) {
            if (channelsBuilder_.isEmpty()) {
              channelsBuilder_.dispose();
              channelsBuilder_ = null;
              channels_ = other.channels_;
              bitField0_ = (bitField0_ & ~0x00000001);
              channelsBuilder_ = 
                com.google.protobuf.GeneratedMessage.alwaysUseFieldBuilders ?
                   getChannelsFieldBuilder() : null;
            } else {
              channelsBuilder_.addAllMessages(other.channels_);
            }
          }
        }
        this.mergeUnknownFields(other.getUnknownFields());
        return this;
      }

      public final boolean isInitialized() {
        for (int i = 0; i < getChannelsCount(); i++) {
          if (!getChannels(i).isInitialized()) {
            
            return false;
          }
        }
        return true;
      }

      public Builder mergeFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws java.io.IOException {
        org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
          parsedMessage = (org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannels) e.getUnfinishedMessage();
          throw e;
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }
      private int bitField0_;

      private java.util.List<org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel> channels_ =
        java.util.Collections.emptyList();
      private void ensureChannelsIsMutable() {
        if (!((bitField0_ & 0x00000001) == 0x00000001)) {
          channels_ = new java.util.ArrayList<org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel>(channels_);
          bitField0_ |= 0x00000001;
         }
      }

      private com.google.protobuf.RepeatedFieldBuilder<
          org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannelOrBuilder> channelsBuilder_;

      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public java.util.List<org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel> getChannelsList() {
        if (channelsBuilder_ == null) {
          return java.util.Collections.unmodifiableList(channels_);
        } else {
          return channelsBuilder_.getMessageList();
        }
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public int getChannelsCount() {
        if (channelsBuilder_ == null) {
          return channels_.size();
        } else {
          return channelsBuilder_.getCount();
        }
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel getChannels(int index) {
        if (channelsBuilder_ == null) {
          return channels_.get(index);
        } else {
          return channelsBuilder_.getMessage(index);
        }
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public Builder setChannels(
          int index, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel value) {
        if (channelsBuilder_ == null) {
          if (value == null) {
            throw new NullPointerException();
          }
          ensureChannelsIsMutable();
          channels_.set(index, value);
          onChanged();
        } else {
          channelsBuilder_.setMessage(index, value);
        }
        return this;
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public Builder setChannels(
          int index, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder builderForValue) {
        if (channelsBuilder_ == null) {
          ensureChannelsIsMutable();
          channels_.set(index, builderForValue.build());
          onChanged();
        } else {
          channelsBuilder_.setMessage(index, builderForValue.build());
        }
        return this;
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public Builder addChannels(org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel value) {
        if (channelsBuilder_ == null) {
          if (value == null) {
            throw new NullPointerException();
          }
          ensureChannelsIsMutable();
          channels_.add(value);
          onChanged();
        } else {
          channelsBuilder_.addMessage(value);
        }
        return this;
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public Builder addChannels(
          int index, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel value) {
        if (channelsBuilder_ == null) {
          if (value == null) {
            throw new NullPointerException();
          }
          ensureChannelsIsMutable();
          channels_.add(index, value);
          onChanged();
        } else {
          channelsBuilder_.addMessage(index, value);
        }
        return this;
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public Builder addChannels(
          org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder builderForValue) {
        if (channelsBuilder_ == null) {
          ensureChannelsIsMutable();
          channels_.add(builderForValue.build());
          onChanged();
        } else {
          channelsBuilder_.addMessage(builderForValue.build());
        }
        return this;
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public Builder addChannels(
          int index, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder builderForValue) {
        if (channelsBuilder_ == null) {
          ensureChannelsIsMutable();
          channels_.add(index, builderForValue.build());
          onChanged();
        } else {
          channelsBuilder_.addMessage(index, builderForValue.build());
        }
        return this;
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public Builder addAllChannels(
          java.lang.Iterable<? extends org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel> values) {
        if (channelsBuilder_ == null) {
          ensureChannelsIsMutable();
          com.google.protobuf.AbstractMessageLite.Builder.addAll(
              values, channels_);
          onChanged();
        } else {
          channelsBuilder_.addAllMessages(values);
        }
        return this;
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public Builder clearChannels() {
        if (channelsBuilder_ == null) {
          channels_ = java.util.Collections.emptyList();
          bitField0_ = (bitField0_ & ~0x00000001);
          onChanged();
        } else {
          channelsBuilder_.clear();
        }
        return this;
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public Builder removeChannels(int index) {
        if (channelsBuilder_ == null) {
          ensureChannelsIsMutable();
          channels_.remove(index);
          onChanged();
        } else {
          channelsBuilder_.remove(index);
        }
        return this;
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder getChannelsBuilder(
          int index) {
        return getChannelsFieldBuilder().getBuilder(index);
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannelOrBuilder getChannelsOrBuilder(
          int index) {
        if (channelsBuilder_ == null) {
          return channels_.get(index);  } else {
          return channelsBuilder_.getMessageOrBuilder(index);
        }
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public java.util.List<? extends org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannelOrBuilder> 
           getChannelsOrBuilderList() {
        if (channelsBuilder_ != null) {
          return channelsBuilder_.getMessageOrBuilderList();
        } else {
          return java.util.Collections.unmodifiableList(channels_);
        }
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder addChannelsBuilder() {
        return getChannelsFieldBuilder().addBuilder(
            org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.getDefaultInstance());
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder addChannelsBuilder(
          int index) {
        return getChannelsFieldBuilder().addBuilder(
            index, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.getDefaultInstance());
      }
      /**
       * <code>repeated .paymentchannels.StoredClientPaymentChannel channels = 1;</code>
       */
      public java.util.List<org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder> 
           getChannelsBuilderList() {
        return getChannelsFieldBuilder().getBuilderList();
      }
      private com.google.protobuf.RepeatedFieldBuilder<
          org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannelOrBuilder> 
          getChannelsFieldBuilder() {
        if (channelsBuilder_ == null) {
          channelsBuilder_ = new com.google.protobuf.RepeatedFieldBuilder<
              org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannelOrBuilder>(
                  channels_,
                  ((bitField0_ & 0x00000001) == 0x00000001),
                  getParentForChildren(),
                  isClean());
          channels_ = null;
        }
        return channelsBuilder_;
      }

      // @@protoc_insertion_point(builder_scope:paymentchannels.StoredClientPaymentChannels)
    }

    static {
      defaultInstance = new StoredClientPaymentChannels(true);
      defaultInstance.initFields();
    }

    // @@protoc_insertion_point(class_scope:paymentchannels.StoredClientPaymentChannels)
  }

  public interface StoredClientPaymentChannelOrBuilder extends
      // @@protoc_insertion_point(interface_extends:paymentchannels.StoredClientPaymentChannel)
      com.google.protobuf.MessageOrBuilder {

    /**
     * <code>required bytes id = 1;</code>
     */
    boolean hasId();
    /**
     * <code>required bytes id = 1;</code>
     */
    com.google.protobuf.ByteString getId();

    /**
     * <code>required bytes contractTransaction = 2;</code>
     */
    boolean hasContractTransaction();
    /**
     * <code>required bytes contractTransaction = 2;</code>
     */
    com.google.protobuf.ByteString getContractTransaction();

    /**
     * <code>required bytes refundTransaction = 3;</code>
     */
    boolean hasRefundTransaction();
    /**
     * <code>required bytes refundTransaction = 3;</code>
     */
    com.google.protobuf.ByteString getRefundTransaction();

    /**
     * <code>required bytes myPublicKey = 8;</code>
     */
    boolean hasMyPublicKey();
    /**
     * <code>required bytes myPublicKey = 8;</code>
     */
    com.google.protobuf.ByteString getMyPublicKey();

    /**
     * <code>required bytes myKey = 4;</code>
     *
     * <pre>
     * Deprecated, key is already stored in the wallet, and found using myPublicKey;
     * </pre>
     */
    boolean hasMyKey();
    /**
     * <code>required bytes myKey = 4;</code>
     *
     * <pre>
     * Deprecated, key is already stored in the wallet, and found using myPublicKey;
     * </pre>
     */
    com.google.protobuf.ByteString getMyKey();

    /**
     * <code>required uint64 valueToMe = 5;</code>
     */
    boolean hasValueToMe();
    /**
     * <code>required uint64 valueToMe = 5;</code>
     */
    long getValueToMe();

    /**
     * <code>required uint64 refundFees = 6;</code>
     *
     * <pre>
     * Fees required to refund the transaction.
     * </pre>
     */
    boolean hasRefundFees();
    /**
     * <code>required uint64 refundFees = 6;</code>
     *
     * <pre>
     * Fees required to refund the transaction.
     * </pre>
     */
    long getRefundFees();

    /**
     * <code>optional bytes closeTransactionHash = 7;</code>
     *
     * <pre>
     * When set, the hash of the transaction that was presented by the server for closure of the channel.
     * It spends the contractTransaction and is expected to be broadcast to the network by the server.
     * It's supposed to be in the wallet already.
     * </pre>
     */
    boolean hasCloseTransactionHash();
    /**
     * <code>optional bytes closeTransactionHash = 7;</code>
     *
     * <pre>
     * When set, the hash of the transaction that was presented by the server for closure of the channel.
     * It spends the contractTransaction and is expected to be broadcast to the network by the server.
     * It's supposed to be in the wallet already.
     * </pre>
     */
    com.google.protobuf.ByteString getCloseTransactionHash();

    /**
     * <code>optional uint32 majorVersion = 9 [default = 1];</code>
     */
    boolean hasMajorVersion();
    /**
     * <code>optional uint32 majorVersion = 9 [default = 1];</code>
     */
    int getMajorVersion();

    /**
     * <code>optional uint64 expiryTime = 10;</code>
     *
     * <pre>
     * The expiry time of the CLTV lock. Only used in protocol v2.
     * </pre>
     */
    boolean hasExpiryTime();
    /**
     * <code>optional uint64 expiryTime = 10;</code>
     *
     * <pre>
     * The expiry time of the CLTV lock. Only used in protocol v2.
     * </pre>
     */
    long getExpiryTime();

    /**
     * <code>optional bytes serverKey = 11;</code>
     *
     * <pre>
     * The server's public key. Only used in protocol v2.
     * </pre>
     */
    boolean hasServerKey();
    /**
     * <code>optional bytes serverKey = 11;</code>
     *
     * <pre>
     * The server's public key. Only used in protocol v2.
     * </pre>
     */
    com.google.protobuf.ByteString getServerKey();
  }
  /**
   * Protobuf type {@code paymentchannels.StoredClientPaymentChannel}
   *
   * <pre>
   * A client-side payment channel in serialized form, which can be reloaded later if the client restarts and wants to
   * reopen an existing channel
   * </pre>
   */
  public static final class StoredClientPaymentChannel extends
      com.google.protobuf.GeneratedMessage implements
      // @@protoc_insertion_point(message_implements:paymentchannels.StoredClientPaymentChannel)
      StoredClientPaymentChannelOrBuilder {
    // Use StoredClientPaymentChannel.newBuilder() to construct.
    private StoredClientPaymentChannel(com.google.protobuf.GeneratedMessage.Builder<?> builder) {
      super(builder);
      this.unknownFields = builder.getUnknownFields();
    }
    private StoredClientPaymentChannel(boolean noInit) { this.unknownFields = com.google.protobuf.UnknownFieldSet.getDefaultInstance(); }

    private static final StoredClientPaymentChannel defaultInstance;
    public static StoredClientPaymentChannel getDefaultInstance() {
      return defaultInstance;
    }

    public StoredClientPaymentChannel getDefaultInstanceForType() {
      return defaultInstance;
    }

    private final com.google.protobuf.UnknownFieldSet unknownFields;
    @java.lang.Override
    public final com.google.protobuf.UnknownFieldSet
        getUnknownFields() {
      return this.unknownFields;
    }
    private StoredClientPaymentChannel(
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
              bitField0_ |= 0x00000001;
              id_ = input.readBytes();
              break;
            }
            case 18: {
              bitField0_ |= 0x00000002;
              contractTransaction_ = input.readBytes();
              break;
            }
            case 26: {
              bitField0_ |= 0x00000004;
              refundTransaction_ = input.readBytes();
              break;
            }
            case 34: {
              bitField0_ |= 0x00000010;
              myKey_ = input.readBytes();
              break;
            }
            case 40: {
              bitField0_ |= 0x00000020;
              valueToMe_ = input.readUInt64();
              break;
            }
            case 48: {
              bitField0_ |= 0x00000040;
              refundFees_ = input.readUInt64();
              break;
            }
            case 58: {
              bitField0_ |= 0x00000080;
              closeTransactionHash_ = input.readBytes();
              break;
            }
            case 66: {
              bitField0_ |= 0x00000008;
              myPublicKey_ = input.readBytes();
              break;
            }
            case 72: {
              bitField0_ |= 0x00000100;
              majorVersion_ = input.readUInt32();
              break;
            }
            case 80: {
              bitField0_ |= 0x00000200;
              expiryTime_ = input.readUInt64();
              break;
            }
            case 90: {
              bitField0_ |= 0x00000400;
              serverKey_ = input.readBytes();
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
        this.unknownFields = unknownFields.build();
        makeExtensionsImmutable();
      }
    }
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return org.bitcoinj.protocols.channels.ClientState.internal_static_paymentchannels_StoredClientPaymentChannel_descriptor;
    }

    protected com.google.protobuf.GeneratedMessage.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return org.bitcoinj.protocols.channels.ClientState.internal_static_paymentchannels_StoredClientPaymentChannel_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.class, org.bitcoinj.protocols.channels.ClientState.StoredClientPaymentChannel.Builder.class);
    }

    public static com.google.protobuf.Parser<StoredClientPaymentChannel> PARSER =
        new com.google.protobuf.AbstractPa