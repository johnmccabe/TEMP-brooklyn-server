package brooklyn.util.guava;

import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import brooklyn.util.javalang.JavaClassNames;

/** Like Guava Optional but permitting null and permitting errors to be thrown. */
public abstract class Maybe<T> {

    public static <T> Maybe<T> absent() {
        return new Absent<T>();
    }
    
    public static <T> Maybe<T> of(@Nullable T value) {
        return new Present<T>(value);
    }
    
    
    public abstract boolean isPresent();
    public abstract T get();
    
    public boolean isPresentAndNonNull() {
        return isPresent() && get()!=null;
    }
    
    public Maybe<T> or(T nextValue) {
        if (isPresent()) return this;
        return of(nextValue);
    }
    
    public static class Absent<T> extends Maybe<T> {
        @Override
        public boolean isPresent() {
            return false;
        }
        @Override
        public T get() {
            throw new NoSuchElementException();
        }
    }

    public static class Present<T> extends Maybe<T> {
        private final T value;
        protected Present(T value) {
            this.value = value;
        }
        @Override
        public boolean isPresent() {
            return true;
        }
        @Override
        public T get() {
            return value;
        }
    }

    @Override
    public String toString() {
        return JavaClassNames.simpleClassName(this)+"["+(isPresent()?"value="+get():"")+"]";
    }

}
