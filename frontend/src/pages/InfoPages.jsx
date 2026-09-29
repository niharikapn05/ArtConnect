import { Link } from "react-router-dom";

export function AboutPage() {
  return (
    <section className="info-page">
      <h1>About ArtConnect</h1>
      <p>ArtConnect is a digital space where creativity meets technology. It is designed to help artists showcase their artwork online and share their visual stories with a wider audience beyond physical boundaries.</p>
      <p>Artists build a public profile, upload their work with descriptions and pricing, sell at a fixed price or through timed auctions, and curate online exhibitions. Collectors browse by category, buy or bid on pieces, and leave reviews that help great work get discovered.</p>
      <p>Many talented artists struggle to find platforms where their work can be presented professionally and discovered easily. ArtConnect was created to bridge that gap with a simple, elegant, and accessible online gallery experience for both creators and viewers.</p>
      <p>Built with React, Spring Boot, PostgreSQL, and Cloudinary, ArtConnect combines cloud image storage with a full-stack architecture to create a responsive and scalable art community.</p>
      <p>ArtConnect is more than a gallery; it is a step toward building a connected digital community for emerging artists.</p>
    </section>
  );
}

export function ContactPage() {
  return (
    <section className="info-page">
      <h1>Contact Me</h1>
      <p>We would love to hear from artists, viewers, and anyone interested in exploring or contributing to the ArtConnect platform.</p>
      <p>If you have suggestions, feedback, collaboration ideas, or questions related to the platform, feel free to reach out using the contact details below.</p>
      <p>Your feedback helps improve the platform and supports the goal of building a more connected and creative online art community.</p>
      <form action="https://formsubmit.co/chandana290403@gmail.com" method="POST">
        <input type="text" name="name" placeholder="Your Name" aria-label="Your name" required />
        <input type="email" name="email" placeholder="Your Email" aria-label="Your email" required />
        <textarea name="message" rows="5" placeholder="Your Message" aria-label="Your message" required />
        <input type="text" name="_honey" className="honeypot" tabIndex="-1" autoComplete="off" />
        <input type="hidden" name="_captcha" value="false" />
        <button type="submit">Send Message</button>
      </form>
      <div className="social-icons">
        <a href="https://www.linkedin.com/in/chandana" target="_blank" rel="noopener noreferrer" aria-label="LinkedIn"><i className="fab fa-linkedin" /></a>
        <a href="https://github.com/chandana" target="_blank" rel="noopener noreferrer" aria-label="GitHub"><i className="fab fa-github" /></a>
        <a href="https://www.instagram.com/chandana" target="_blank" rel="noopener noreferrer" aria-label="Instagram"><i className="fab fa-instagram" /></a>
        <a href="mailto:chandana290403@gmail.com" aria-label="Email Chandana"><i className="fas fa-envelope" /></a>
      </div>
    </section>
  );
}

export function NotFoundPage() {
  return (
    <section className="container page-section text-center">
      <h1>Page not found</h1>
      <Link to="/" className="btn btn-primary">Return home</Link>
    </section>
  );
}
