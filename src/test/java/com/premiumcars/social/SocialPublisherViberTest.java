package com.premiumcars.social;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.repo.CarImageRepository;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.CarPhotoFiles;

class SocialPublisherViberTest {

    private final ViberChannelClient viber = mock(ViberChannelClient.class);
    private final SocialPostRepository posts = mock(SocialPostRepository.class);
    private SocialPublisher publisher;

    @BeforeEach
    void setUp() {
        AppProperties props = mock(AppProperties.class);
        AppProperties.Social social = mock(AppProperties.Social.class);
        when(props.social()).thenReturn(social);
        when(social.viberConfigured()).thenReturn(true);
        CarRepository cars = mock(CarRepository.class);
        when(cars.findById(7L)).thenReturn(Optional.of(mock(Car.class)));
        when(viber.superadminId()).thenReturn("admin-id");
        when(viber.postText(anyString(), anyString())).thenReturn("text-token");
        when(viber.postUrl(anyString(), anyString())).thenReturn("url-token");
        publisher = new SocialPublisher(props, cars, mock(CarImageRepository.class), mock(CarPhotoFiles.class),
                mock(MetaGraphClient.class), mock(ImgbbUploader.class), posts, viber);
    }

    @Test
    void testPublishLinkOnly_TextAndLink_LinkMessageThenText() {
        publisher.publishLinkOnly(7, SocialNetwork.VIBER, "Крайна цена", "https://x.bg/cars/a");

        InOrder order = inOrder(viber);
        order.verify(viber).postUrl("admin-id", "https://x.bg/cars/a");
        order.verify(viber).postText("admin-id", "Крайна цена");
        verify(posts).insert(7, SocialNetwork.VIBER, "text-token", null, 0);
    }

    @Test
    void testPublishLinkOnly_LinkRefused_LinkSentAsText() {
        when(viber.postUrl(anyString(), anyString())).thenThrow(new BadRequestException("refused"));

        publisher.publishLinkOnly(7, SocialNetwork.VIBER, "Крайна цена", "https://x.bg/cars/a");

        verify(viber).postText("admin-id", "https://x.bg/cars/a");
        verify(posts).insert(7, SocialNetwork.VIBER, "text-token", null, 0);
    }

    @Test
    void testPublishLinkOnly_LinkAlone_LinkMessage() {
        publisher.publishLinkOnly(7, SocialNetwork.VIBER, "https://x.bg/cars/a", null);

        verify(viber).postUrl("admin-id", "https://x.bg/cars/a");
        verify(viber, never()).postText(anyString(), anyString());
        verify(posts).insert(eq(7L), eq(SocialNetwork.VIBER), eq("url-token"), isNull(), anyInt());
    }

    @Test
    void testValidateLinkOnly_NotALink_Rejected() {
        assertThatThrownBy(() -> publisher.validateLinkOnly(7, SocialNetwork.VIBER, "Крайна цена", "не е линк"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("web address");
        verify(posts, never()).insert(anyLong(), eq(SocialNetwork.VIBER), anyString(), isNull(), anyInt());
    }
}
